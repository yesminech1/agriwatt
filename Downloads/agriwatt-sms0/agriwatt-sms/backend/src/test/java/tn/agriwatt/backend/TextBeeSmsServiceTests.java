package tn.agriwatt.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tn.agriwatt.backend.service.PhoneNumbers;
import tn.agriwatt.backend.service.TextBeeException;
import tn.agriwatt.backend.service.TextBeeSmsService;
import tools.jackson.databind.json.JsonMapper;

/** TextBeeSmsService contre un faux serveur TextBee (aucun SMS réel). */
class TextBeeSmsServiceTests {

    static final String KEY = "cle-de-test-ne-pas-afficher";
    static volatile int status = 200;
    static volatile String body = "";
    static final List<String[]> REQUESTS = new CopyOnWriteArrayList<>();   // {méthode, chemin+requête, x-api-key, corps}
    static final HttpServer SERVER = start();

    static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/api/v1/", ex -> {
                String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                REQUESTS.add(new String[]{ex.getRequestMethod(), ex.getRequestURI().toString(),
                        ex.getRequestHeaders().getFirst("x-api-key"), reqBody});
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
                if (out.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(out); }
            });
            s.start();
            return s;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    @AfterAll static void stop() { SERVER.stop(0); }

    TextBeeSmsService svc;

    @BeforeEach
    void setUp() {
        REQUESTS.clear();
        svc = new TextBeeSmsService("http://localhost:" + SERVER.getAddress().getPort() + "/api/v1", KEY, "", JsonMapper.builder().build());
    }

    private static void reply(int s, String b) { status = s; body = b; }

    @Test
    void envoiAccepteRenvoieLeSmsBatchIdEtUtiliseLaCleEnEntete() {
        reply(200, "{\"data\":{\"success\":true,\"message\":\"SMS added to queue for processing\",\"smsBatchId\":\"66b1f2c3\",\"recipientCount\":1}}");
        TextBeeSmsService.SendResult r = svc.send("+21622345678", "Alerte AgriWatt : test");
        assertEquals("66b1f2c3", r.smsBatchId());
        assertNull(r.warningCode());
        String[] req = REQUESTS.get(0);
        assertEquals("POST", req[0]);
        assertEquals("/api/v1/gateway/send-sms", req[1]);
        assertEquals(KEY, req[2]);
        assertTrue(req[3].contains("\"recipients\":[\"+21622345678\"]") && req[3].contains("Alerte AgriWatt : test"), req[3]);
    }

    @Test
    void avertissementPermissionSms() {
        reply(200, "{\"data\":{\"success\":true,\"smsBatchId\":\"b2\",\"recipientCount\":1,\"warning\":{\"code\":\"SMS_PERMISSION_MISSING\"}}}");
        assertEquals("SMS_PERMISSION_MISSING", svc.send("+21622345678", "x").warningCode());
    }

    @Test
    void erreursClasseesSansJamaisReveleLaCle() {
        Object[][] cases = {
                {401, "{\"message\":\"Unauthorized\"}", TextBeeException.Kind.INVALID_API_KEY, false},
                {403, "{\"message\":\"Forbidden\"}", TextBeeException.Kind.ACCOUNT_FORBIDDEN, false},
                {429, "{\"message\":\"Daily limit reached\"}", TextBeeException.Kind.RATE_LIMITED, true},
                {400, "{\"success\":false,\"error\":\"No enabled device found\"}", TextBeeException.Kind.DEVICE_UNAVAILABLE, true},
                {400, "{\"message\":[\"recipient must not contain letters\"]}", TextBeeException.Kind.INVALID_NUMBER, false},
                {503, "", TextBeeException.Kind.SERVER_ERROR, true}};
        for (Object[] c : cases) {
            reply((int) c[0], (String) c[1]);
            TextBeeException e = assertThrows(TextBeeException.class, () -> svc.send("+21622345678", "x"));
            assertEquals(c[2], e.getKind(), "HTTP " + c[0]);
            assertEquals(c[3], e.getKind().retryable, "HTTP " + c[0]);
            assertFalse(e.getMessage().contains(KEY), "la clé ne doit jamais apparaître dans un message");
        }
    }

    @Test
    void erreurReseauEtCleAbsente() {
        TextBeeSmsService offline = new TextBeeSmsService("http://localhost:1/api/v1", KEY, "", JsonMapper.builder().build());
        assertEquals(TextBeeException.Kind.NETWORK, assertThrows(TextBeeException.class, () -> offline.send("+21622345678", "x")).getKind());
        TextBeeSmsService noKey = new TextBeeSmsService("http://localhost:1/api/v1", "", "", JsonMapper.builder().build());
        assertFalse(noKey.isConfigured());
        assertEquals(TextBeeException.Kind.NOT_CONFIGURED, assertThrows(TextBeeException.class, () -> noKey.send("+21622345678", "x")).getKind());
        assertFalse(noKey.gatewayStatus().configured());
    }

    @Test
    void suiviDuStatutParSmsBatchId() {
        reply(200, "{\"data\":[{\"_id\":\"m1\",\"recipient\":\"+21622345678\",\"status\":\"delivered\",\"smsBatch\":\"b1\"}],\"meta\":{}}");
        List<TextBeeSmsService.MessageStatus> st = svc.batchStatus("b1");
        assertEquals("delivered", st.get(0).status());
        assertTrue(REQUESTS.get(0)[1].startsWith("/api/v1/gateway/messages?smsBatchId=b1"), REQUESTS.get(0)[1]);
    }

    @Test
    void testDeConnexion() {
        reply(200, "{\"data\":[{\"_id\":\"d1\",\"name\":\"Pixel AgriWatt\",\"enabled\":true,\"isDefault\":true,\"lastHeartbeat\":\"2026-11-22T00:10:00Z\"}]}");
        TextBeeSmsService.GatewayStatus g = svc.gatewayStatus();
        assertTrue(g.configured() && g.reachable());
        assertEquals(1, g.enabledDevices());
        assertEquals("Pixel AgriWatt", g.defaultDeviceName());
        reply(401, "{\"message\":\"Unauthorized\"}");
        assertFalse(svc.gatewayStatus().reachable());
    }

    @Test
    void normalisationDesNumeros() {
        assertEquals("+21622345678", PhoneNumbers.normalize("22 345 678"));
        assertEquals("+21622345678", PhoneNumbers.normalize("+216 22-345-678"));
        assertEquals("+21622345678", PhoneNumbers.normalize("0021622345678"));
        assertEquals("+33612345678", PhoneNumbers.normalize("+33 6 12 34 56 78"));
        assertNull(PhoneNumbers.normalize("  "));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumbers.normalize("2234"));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumbers.normalize("+2162234567"));   // 7 chiffres après +216
        assertThrows(IllegalArgumentException.class, () -> PhoneNumbers.normalize("abc"));
    }
}
