package tn.agriwatt.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tn.agriwatt.backend.entity.AppUser;
import tn.agriwatt.backend.repository.AppUserRepository;
import tn.agriwatt.backend.repository.FarmRepository;

/** Intégration B + C : authentification (comptes en base) et API protégée utilisée par le front. */
@SpringBootTest
class AuthAndApiIntegrationTests {

    @Autowired WebApplicationContext context;
    @Autowired AppUserRepository users;
    @Autowired FarmRepository farms;
    @Autowired tn.agriwatt.backend.repository.PlanRunRepository runs;
    @Autowired tn.agriwatt.backend.repository.ScheduleHourRepository scheduleHours;
    @Autowired tn.agriwatt.backend.repository.AnomalyEventRepository anomalies;
    @Autowired tn.agriwatt.backend.repository.SmsAlertRepository smsAlerts;
    @Autowired tn.agriwatt.backend.repository.PlantAnalysisRepository plantAnalyses;
    @Autowired PasswordEncoder encoder;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .defaultResponseCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8).build();
        smsAlerts.deleteAll();
        plantAnalyses.deleteAll();  // les analyses référencent les utilisateurs (clé étrangère)
        scheduleHours.deleteAll();
        runs.deleteAll();
        anomalies.deleteAll();
        farms.deleteAll();          // les fermes référencent les utilisateurs (clé étrangère)
        users.deleteAll();
        users.saveAndFlush(new AppUser("Test Agriculteur", "test@exemple.tn", encoder.encode("motdepasse123")));
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@exemple.tn\",\"password\":\"motdepasse123\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    @Test
    void anonymeRedirigeVersLoginEtApiRefusee() throws Exception {
        mvc.perform(get("/")).andExpect(status().isFound()).andExpect(redirectedUrl("/login.html"));
        mvc.perform(get("/api/config/default")).andExpect(status().isUnauthorized());
        mvc.perform(get("/login.html")).andExpect(status().isOk());
    }

    @Test
    void inscriptionStockeUniquementLeHash() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Autre Agriculteur\",\"email\":\"Autre@Exemple.tn\",\"password\":\"motdepasse123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("autre@exemple.tn"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        AppUser u = users.findByEmail("autre@exemple.tn").orElseThrow();
        Assertions.assertTrue(u.getPasswordHash().startsWith("$2a$"));

        // même e-mail -> 409
        mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Doublon\",\"email\":\"autre@exemple.tn\",\"password\":\"motdepasse123\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void mauvaisMotDePasseRefuse() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@exemple.tn\",\"password\":\"faux-mot-de-passe\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sansMeteoReelleLeCalculEchoueClairementSansDonneesFictives() throws Exception {
        MockHttpSession session = login();
        mvc.perform(get("/api/config/default").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario").doesNotExist());

        String farmJson = "{\"name\":\"Ferme\",\"lat\":33.92,\"lon\":8.13,\"crop\":\"olive\",\"areaM2\":20000,"
                + "\"panelAreaM2\":24,\"panelEfficiency\":0.18,\"pumpPowerKw\":5,\"pumpFlowM3h\":10,"
                + "\"tankCapacityM3\":30,\"tankInitialM3\":10,\"days\":2,"
                + "\"offPeakTariff\":0.2,\"normalTariff\":0.3,\"peakTariff\":0.45}";
        String created = mvc.perform(post("/api/farms").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(farmJson))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long farmId = ((Number) com.jayway.jsonpath.JsonPath.read(created, "$.id")).longValue();

        // service Python éteint (port fermé dans les tests) : 503 explicite, rien d'enregistré, aucun secours fictif
        mvc.perform(post("/api/plan").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(FakePythonTestBase.planBody(farmId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("injoignable")));
        mvc.perform(get("/api/runs").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/anomalies").session(session)).andExpect(status().isNotFound());   // ancien endpoint de démo supprimé

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Test Agriculteur"));
    }

    @Test
    void postSansJetonCsrfRefuse() throws Exception {
        MockHttpSession session = login();
        mvc.perform(post("/api/plan").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
    @Test
    void detectionAvecServicePythonEteintRepond503SansRienEnregistrer() throws Exception {
        MockHttpSession session = login();
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/detection/analyze")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "olivier.jpg", "image/jpeg", jpeg))
                        .param("plantType", "olivier").session(session).with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("injoignable")));
        Assertions.assertEquals(0, plantAnalyses.count());
        // sans jeton CSRF : refusé
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/detection/analyze")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "olivier.jpg", "image/jpeg", jpeg))
                        .session(session))
                .andExpect(status().isForbidden());
    }
}
