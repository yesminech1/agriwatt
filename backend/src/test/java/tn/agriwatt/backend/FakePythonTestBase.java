package tn.agriwatt.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;

import tn.agriwatt.backend.entity.AppUser;
import tn.agriwatt.backend.repository.AnomalyEventRepository;
import tn.agriwatt.backend.repository.AppUserRepository;
import tn.agriwatt.backend.repository.FarmRepository;
import tn.agriwatt.backend.repository.PlanRunRepository;
import tn.agriwatt.backend.repository.ScheduleHourRepository;

/**
 * Base des tests d'intégration : un FAUX service Python (serveur HTTP du JDK) remplace FastAPI.
 * Il enregistre les requêtes /forecast reçues (pour vérifier les coordonnées) et répond comme Open-Meteo + calculs.
 * Ces données n'existent que dans les tests.
 */
abstract class FakePythonTestBase {

    static final List<String> PYTHON_QUERIES = new CopyOnWriteArrayList<>();
    static final HttpServer FAKE_PYTHON = startFakePython();

    // --- faux TextBee : enregistre les envois, réponse paramétrable ---
    static final String TEXTBEE_TEST_KEY = "cle-textbee-de-test";
    static final List<String[]> TEXTBEE_REQUESTS = new CopyOnWriteArrayList<>();   // {chemin, x-api-key, corps}
    static volatile int textBeeSendStatus = 200;
    static volatile String textBeeSendError = "";
    static volatile String textBeeMessageStatus = "delivered";
    static final java.util.concurrent.atomic.AtomicInteger BATCH = new java.util.concurrent.atomic.AtomicInteger();
    static final HttpServer FAKE_TEXTBEE = startFakeTextBee();

    // --- faux modèle de détection (/detection/*) : réponse paramétrable, requêtes multipart enregistrées ---
    static final String DETECTION_OK = "{\"success\":true,\"plantType\":\"olivier\",\"plantLabel\":\"Olivier\","
            + "\"prediction\":\"STRESS_HYDRIQUE\",\"predictionLabel\":\"Signes compatibles avec un stress hydrique\","
            + "\"modelClass\":\"STRESS_HYDRIQUE\",\"confidence\":0.8312,"
            + "\"probabilities\":{\"SANS_STRESS\":0.1688,\"STRESS_HYDRIQUE\":0.8312},\"uncertainThreshold\":0.65,"
            + "\"interpretation\":\"Signes visuels proches des oliviers non irrigués.\","
            + "\"recommendation\":\"Vérifiez l'humidité du sol.\",\"modelVersion\":\"test-1\"}";
    static final List<String> DETECTION_REQUESTS = new CopyOnWriteArrayList<>();   // corps multipart (ISO-8859-1)
    static volatile int detectionStatus = 200;
    static volatile String detectionBody = DETECTION_OK;

    @DynamicPropertySource
    static void pythonUrl(DynamicPropertyRegistry registry) {
        registry.add("python.service.url", () -> "http://localhost:" + FAKE_PYTHON.getAddress().getPort());
        registry.add("textbee.base-url", () -> "http://localhost:" + FAKE_TEXTBEE.getAddress().getPort() + "/api/v1");
        registry.add("textbee.api-key", () -> TEXTBEE_TEST_KEY);
    }

    private static HttpServer startFakeTextBee() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/api/v1/gateway/send-sms", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                TEXTBEE_REQUESTS.add(new String[]{ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("x-api-key"), body});
                if (textBeeSendStatus != 200) { sendStatus(ex, textBeeSendStatus, textBeeSendError); return; }
                sendStatus(ex, 200, "{\"data\":{\"success\":true,\"message\":\"SMS added to queue for processing\",\"smsBatchId\":\"batch-"
                        + BATCH.incrementAndGet() + "\",\"recipientCount\":1}}");
            });
            server.createContext("/api/v1/gateway/messages", ex -> {
                TEXTBEE_REQUESTS.add(new String[]{ex.getRequestURI().toString(), ex.getRequestHeaders().getFirst("x-api-key"), ""});
                sendStatus(ex, 200, "{\"data\":[{\"_id\":\"m1\",\"recipient\":\"+21622345678\",\"status\":\"" + textBeeMessageStatus + "\"}]}");
            });
            server.createContext("/api/v1/gateway/devices", ex -> sendStatus(ex, 200,
                    "{\"data\":[{\"_id\":\"d1\",\"name\":\"Pixel AgriWatt\",\"enabled\":true,\"isDefault\":true,\"lastHeartbeat\":\"2026-11-22T00:10:00Z\"}]}"));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sendStatus(com.sun.net.httpserver.HttpExchange ex, int status, String json) throws IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        if (out.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }

    @Autowired WebApplicationContext context;
    @Autowired AppUserRepository users;
    @Autowired FarmRepository farmRepo;
    @Autowired PlanRunRepository runRepo;
    @Autowired ScheduleHourRepository scheduleRepo;
    @Autowired AnomalyEventRepository anomalyRepo;
    @Autowired tn.agriwatt.backend.repository.SmsAlertRepository smsAlertRepo;
    @Autowired tn.agriwatt.backend.repository.PlantAnalysisRepository plantAnalysisRepo;
    @Autowired PasswordEncoder encoder;
    MockMvc mvc;

    static final double[] TOZEUR = {33.9197, 8.1335};
    static final double[] SFAX = {34.7406, 10.7603};

    @BeforeEach
    void resetData() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity())
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8).build();
        smsAlertRepo.deleteAll();
        plantAnalysisRepo.deleteAll();
        scheduleRepo.deleteAll();
        runRepo.deleteAll();
        anomalyRepo.deleteAll();
        farmRepo.deleteAll();
        users.deleteAll();
        users.saveAndFlush(new AppUser("Agriculteur A", "a@exemple.tn", encoder.encode("motdepasseA1")));
        users.saveAndFlush(new AppUser("Agriculteur B", "b@exemple.tn", encoder.encode("motdepasseB1")));
        PYTHON_QUERIES.clear();
        TEXTBEE_REQUESTS.clear();
        textBeeSendStatus = 200;
        textBeeSendError = "";
        textBeeMessageStatus = "delivered";
        DETECTION_REQUESTS.clear();
        detectionStatus = 200;
        detectionBody = DETECTION_OK;
    }

    MockHttpSession login(String email, String password) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    static String farm(String name, Object lat, Object lon) {
        return "{\"name\":\"" + name + "\",\"lat\":" + lat + ",\"lon\":" + lon + ",\"crop\":\"olive\",\"areaM2\":20000,"
                + "\"panelAreaM2\":24,\"panelEfficiency\":0.18,\"pumpPowerKw\":5,\"pumpFlowM3h\":10,\"tankCapacityM3\":30,"
                + "\"tankInitialM3\":10,\"outageStartHour\":14,\"outageEndHour\":19,\"days\":2,"
                + "\"offPeakTariff\":0.2,\"normalTariff\":0.3,\"peakTariff\":0.45}";
    }

    long create(MockHttpSession s, String json) throws Exception {
        String body = mvc.perform(post("/api/farms").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** Corps envoyé par le front : seul farmId compte, les autres valeurs (volontairement fausses) sont ignorées. */
    static String planBody(long farmId) {
        return "{\"lat\":36.8,\"lon\":10.18,\"crop\":\"ble\",\"areaM2\":1,\"panelAreaM2\":1,\"panelEfficiency\":0.18,"
                + "\"pumpPowerKw\":5,\"pumpFlowM3h\":10,\"tankCapacityM3\":99,\"tankInitialM3\":0,\"days\":2,"
                + "\"tariff\":{\"offPeak\":0.2,\"normal\":0.3,\"peak\":0.45},\"farmId\":" + farmId + "}";
    }

    String plan(MockHttpSession s, long farmId) throws Exception {
        return mvc.perform(post("/api/plan").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(planBody(farmId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    static String lastQuery() { return PYTHON_QUERIES.get(PYTHON_QUERIES.size() - 1); }

    private static HttpServer startFakePython() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/forecast", exchange -> {
                String q = exchange.getRequestURI().getRawQuery();
                PYTHON_QUERIES.add(q);
                double lat = Double.parseDouble(param(q, "lat"));
                StringBuilder hours = new StringBuilder();
                for (int i = 0; i < 48; i++) {
                    int h = i % 24;
                    double sun = (h >= 7 && h <= 17) ? 800 : 0;
                    hours.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT,
                            "{\"hour\":\"2026-07-%02dT%02d:00\",\"tempC\":%.1f,\"irradianceWm2\":%.1f,\"et0Mm\":%.2f,"
                                    + "\"pvKw\":%.3f,\"outageRisk\":0.3,\"waterNeedM3\":%.2f}",
                            15 + i / 24, h, 70 - lat, sun, sun > 0 ? 0.5 : 0, sun * 24 * 0.18 * 0.8 / 1000, sun > 0 ? 7.0 : 0));
                }
                send(exchange, "{\"hours\":[" + hours + "],\"source\":\"openmeteo\",\"lat\":" + param(q, "lat")
                        + ",\"lon\":" + param(q, "lon") + "}");
            });
            server.createContext("/anomalies/detect", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                List<Map<String, Object>> pts = JsonPath.read(body, "$.points");
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < pts.size(); i++) {
                    double p = ((Number) pts.get(i).get("powerKw")).doubleValue();
                    boolean anomaly = p > 6.0 || (p > 0.3 && p < 2.0);
                    out.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT,
                            "{\"timestamp\":\"%s\",\"powerKw\":%.2f,\"isAnomaly\":%b,\"score\":%.2f,\"reason\":\"%s\"}",
                            pts.get(i).get("timestamp"), p, anomaly, anomaly ? -0.2 : 0.1,
                            anomaly ? (p > 6 ? "Consommation élevée (usure probable)" : "Puissance très basse : marche à vide probable")
                                    : (p > 0.3 ? "Fonctionnement normal" : "Pompe à l'arrêt")));
                }
                send(exchange, "{\"points\":[" + out + "]}");
            });
            server.createContext("/detection/analyze", exchange -> {
                DETECTION_REQUESTS.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
                sendStatus(exchange, detectionStatus, detectionBody);
            });
            server.createContext("/detection/model", exchange -> sendStatus(exchange, 200,
                    "{\"available\":true,\"version\":\"test-1\",\"plants\":[{\"code\":\"olivier\",\"label\":\"Olivier\"}],"
                            + "\"metrics\":{\"test\":{\"accuracy\":0.8,\"f1\":0.79}}}"));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
    }

    static String param(String query, String name) {
        for (String kv : query.split("&")) if (kv.startsWith(name + "=")) return kv.substring(name.length() + 1);
        return "null";
    }
}
