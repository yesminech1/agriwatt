package tn.agriwatt.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import tn.agriwatt.backend.dto.ForecastHour;
import tn.agriwatt.backend.entity.AppUser;
import tn.agriwatt.backend.entity.Farm;
import tn.agriwatt.backend.entity.SmsAlert;
import tn.agriwatt.backend.service.OutageAlertService;

/** Alertes SMS : numéro, activation, prédiction, programmation 2 h avant, envoi TextBee, statuts, doublons, suivi. */
@SpringBootTest
class SmsAlertIntegrationTests extends FakePythonTestBase {

    @Autowired OutageAlertService outageAlerts;

    static final LocalDateTime DAY = LocalDateTime.of(2026, 7, 15, 0, 0);

    /** 48 h de prévision (données de TEST) : risque 1.0 sur les heures données, 0.3 ailleurs. */
    static List<ForecastHour> forecast(double risk, int... riskyHours) {
        List<ForecastHour> out = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            int h = i % 24;
            boolean risky = false;
            for (int r : riskyHours) risky |= r == h;
            out.add(new ForecastHour(DAY.plusHours(i).toString(), 35, 0, 0, 0, risky ? risk : 0.3, 0));
        }
        return out;
    }

    private MockHttpSession enableAlerts(String email, String pwd, String phone) throws Exception {
        MockHttpSession s = login(email, pwd);
        mvc.perform(put("/api/account/alerts").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"" + phone + "\",\"smsAlertsEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.smsAlertsEnabled").value(true))
                .andExpect(jsonPath("$.smsGatewayConfigured").value(true));
        return s;
    }

    private Farm farmEntity(MockHttpSession s, String name) throws Exception {
        return farmRepo.findById(create(s, farm(name, TOZEUR[0], TOZEUR[1]))).orElseThrow();   // coupure prévue 14h-19h
    }

    private List<SmsAlert> alertsOf(Farm f) {
        return smsAlertRepo.findAll().stream().filter(a -> a.getFarm() != null && a.getFarm().getId().equals(f.getId())).toList();
    }

    // ---------- numéro et activation ----------

    @Test
    void inscriptionAvecNumeroEtValidation() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Agri C\",\"email\":\"c@exemple.tn\",\"password\":\"motdepasseC1\",\"phoneNumber\":\"22 345 678\",\"smsAlerts\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("+21622345678"))
                .andExpect(jsonPath("$.smsAlertsEnabled").value(true));
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Agri D\",\"email\":\"d@exemple.tn\",\"password\":\"motdepasseD1\",\"phoneNumber\":\"12ab\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Agri E\",\"email\":\"e@exemple.tn\",\"password\":\"motdepasseE1\",\"smsAlerts\":true}"))
                .andExpect(status().isBadRequest());
        AppUser c = users.findByEmail("c@exemple.tn").orElseThrow();
        Assertions.assertEquals("+21622345678", c.getPhoneNumber());
    }

    @Test
    void parametresModifierNumeroActiverDesactiver() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        mvc.perform(put("/api/account/alerts").session(a).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"\",\"smsAlertsEnabled\":true}")).andExpect(status().isBadRequest());
        enableAlerts("a@exemple.tn", "motdepasseA1", "+216 22 345 678");
        mvc.perform(get("/api/account/alerts").session(a))
                .andExpect(jsonPath("$.phoneNumber").value("+21622345678"))
                .andExpect(jsonPath("$.leadHours").value(2));
        Farm f = farmEntity(a, "Ferme A");
        outageAlerts.scheduleFromForecast(f, forecast(1.0, 14, 15, 16, 17, 18), DAY.plusHours(8));
        Assertions.assertEquals(2, alertsOf(f).stream().filter(x -> x.getStatus() == SmsAlert.Status.PENDING).count());
        mvc.perform(put("/api/account/alerts").session(a).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"+21622345678\",\"smsAlertsEnabled\":false}")).andExpect(status().isOk());
        Assertions.assertTrue(alertsOf(f).stream().allMatch(x -> x.getStatus() == SmsAlert.Status.CANCELLED), "désactivation = alertes annulées");
    }

    // ---------- prédiction et programmation ----------

    @Test
    void coupurePreditePuisAlerteProgrammeeDeuxHeuresAvantSansDoublon() throws Exception {
        MockHttpSession a = enableAlerts("a@exemple.tn", "motdepasseA1", "+21622345678");
        Farm f = farmEntity(a, "Ferme A");
        LocalDateTime now = DAY.plusHours(8);                                        // 15/07 08:00
        outageAlerts.scheduleFromForecast(f, forecast(1.0, 15, 16, 17, 18), now);     // coupure prévue 15h-19h, 2 jours
        List<SmsAlert> list = alertsOf(f);
        Assertions.assertEquals(2, list.size());
        SmsAlert first = list.stream().filter(x -> x.getOutageStart().equals(DAY.plusHours(15))).findFirst().orElseThrow();
        Assertions.assertEquals(SmsAlert.Status.PENDING, first.getStatus());
        Assertions.assertEquals(DAY.plusHours(13), first.getScheduledAt(), "envoi 2 h avant 15h00");
        Assertions.assertEquals(DAY.plusHours(19), first.getOutageEnd());
        Assertions.assertEquals("Alerte AgriWatt : une coupure d'électricité est prévue vers 15h00. "
                + "Anticipez votre irrigation et remplissez votre réservoir.", first.getMessage());

        outageAlerts.scheduleFromForecast(f, forecast(1.0, 15, 16, 17, 18), now.plusHours(1));   // nouvelle prévision identique
        Assertions.assertEquals(2, alertsOf(f).size(), "aucun doublon");

        outageAlerts.scheduleFromForecast(f, forecast(1.0), now.plusHours(2));                    // plus de coupure prévue
        Assertions.assertTrue(alertsOf(f).stream().allMatch(x -> x.getStatus() == SmsAlert.Status.CANCELLED));
    }

    @Test
    void coupureDansMoinsDeDeuxHeuresEtRisqueEstime() throws Exception {
        MockHttpSession a = enableAlerts("a@exemple.tn", "motdepasseA1", "+21622345678");
        Farm f = farmEntity(a, "Ferme A");
        f.setOutageStartHour(null); f.setOutageEndHour(null); farmRepo.save(f);           // pas d'horaires saisis : risque estimé
        LocalDateTime now = DAY.plusHours(14);
        outageAlerts.scheduleFromForecast(f, forecast(0.9, 15), now);
        SmsAlert x = alertsOf(f).stream().filter(al -> al.getOutageStart().equals(DAY.plusHours(15))).findFirst().orElseThrow();
        Assertions.assertEquals(now, x.getScheduledAt(), "coupure dans 1 h : envoi immédiat");
        Assertions.assertTrue(x.getMessage().contains("un risque élevé de coupure d'électricité est estimé vers 15h00"), x.getMessage());
        Assertions.assertFalse(x.getPlannedOutage());
    }

    @Test
    void aucuneAlerteSiNonActiveeEtPlusieursFermesNommees() throws Exception {
        MockHttpSession b = login("b@exemple.tn", "motdepasseB1");
        Farm fb = farmEntity(b, "Ferme B");
        outageAlerts.scheduleFromForecast(fb, forecast(1.0, 15), DAY.plusHours(8));
        Assertions.assertTrue(alertsOf(fb).isEmpty(), "alertes non activées : rien n'est programmé");

        MockHttpSession a = enableAlerts("a@exemple.tn", "motdepasseA1", "+21622345678");
        Farm f1 = farmEntity(a, "Oliveraie Nord");
        farmEntity(a, "Oliveraie Sud");
        outageAlerts.scheduleFromForecast(f1, forecast(1.0, 15), DAY.plusHours(8));
        Assertions.assertTrue(alertsOf(f1).get(0).getMessage().startsWith("Alerte AgriWatt (Oliveraie Nord) :"));
    }

    // ---------- envoi via TextBee, statuts ----------

    @Test
    void envoiALHeureProgrammeeEtSuiviDeLivraison() throws Exception {
        MockHttpSession a = enableAlerts("a@exemple.tn", "motdepasseA1", "+21622345678");
        Farm f = farmEntity(a, "Ferme A");
        outageAlerts.scheduleFromForecast(f, forecast(1.0, 15, 16), DAY.plusHours(8));
        Assertions.assertEquals(0, outageAlerts.dispatchDue(DAY.plusHours(12).plusMinutes(59)), "pas encore l'heure");
        Assertions.assertTrue(TEXTBEE_REQUESTS.isEmpty());

        outageAlerts.dispatchDue(DAY.plusHours(13));
        SmsAlert sent = alertsOf(f).stream().filter(x -> x.getOutageStart().equals(DAY.plusHours(15))).findFirst().orElseThrow();
        Assertions.assertEquals(SmsAlert.Status.SENT, sent.getStatus());
        Assertions.assertTrue(sent.getSmsBatchId().startsWith("batch-"));
        Assertions.assertEquals("pending", sent.getProviderStatus(), "200 = mis en file d'attente, pas livré");
        String[] req = TEXTBEE_REQUESTS.get(0);
        Assertions.assertEquals("/api/v1/gateway/send-sms", req[0]);
        Assertions.assertEquals(TEXTBEE_TEST_KEY, req[1]);
        Assertions.assertTrue(req[2].contains("\"recipients\":[\"+21622345678\"]") && req[2].contains("vers 15h00"), req[2]);

        outageAlerts.dispatchDue(DAY.plusHours(13).plusMinutes(5));
        Assertions.assertEquals(1, TEXTBEE_REQUESTS.stream().filter(r -> r[0].endsWith("send-sms")).count(), "pas de second SMS");

        outageAlerts.trackDeliveries(DAY.plusHours(13).plusMinutes(10));
        Assertions.assertEquals("delivered", smsAlertRepo.findById(sent.getId()).orElseThrow().getProviderStatus());
    }

    @Test
    void erreursTextBeeTemporairesDefinitivesEtCleInvalide() throws Exception {
        MockHttpSession a = enableAlerts("a@exemple.tn", "motdepasseA1", "+21622345678");
        Farm f = farmEntity(a, "Ferme A");
        outageAlerts.scheduleFromForecast(f, forecast(1.0, 15), DAY.plusHours(8));
        SmsAlert alert = alertsOf(f).stream().filter(x -> x.getOutageStart().equals(DAY.plusHours(15))).findFirst().orElseThrow();

        textBeeSendStatus = 429; textBeeSendError = "{\"message\":\"Daily limit reached\"}";
        outageAlerts.dispatchDue(DAY.plusHours(13));
        SmsAlert x = smsAlertRepo.findById(alert.getId()).orElseThrow();
        Assertions.assertEquals(SmsAlert.Status.PENDING, x.getStatus());
        Assertions.assertEquals(1, x.getAttempts());
        Assertions.assertTrue(x.getLastError().contains("Limite"), x.getLastError());

        textBeeSendStatus = 401; textBeeSendError = "{\"message\":\"Unauthorized\"}";
        outageAlerts.dispatchDue(DAY.plusHours(13).plusMinutes(30));
        x = smsAlertRepo.findById(alert.getId()).orElseThrow();
        Assertions.assertEquals(SmsAlert.Status.PENDING, x.getStatus(), "clé invalide : on attend la correction");
        Assertions.assertFalse(x.getLastError().contains(TEXTBEE_TEST_KEY));

        textBeeSendStatus = 400; textBeeSendError = "{\"message\":[\"recipient must not contain letters\"]}";
        outageAlerts.dispatchDue(DAY.plusHours(14));
        x = smsAlertRepo.findById(alert.getId()).orElseThrow();
        Assertions.assertEquals(SmsAlert.Status.FAILED, x.getStatus(), "numéro refusé : échec définitif");
    }

    @Test
    void coupureDejaCommenceeEtLivraisonEchouee() throws Exception {
        MockHttpSession a = enableAlerts("a@exemple.tn", "motdepasseA1", "+21622345678");
        Farm f = farmEntity(a, "Ferme A");
        outageAlerts.scheduleFromForecast(f, forecast(1.0, 15), DAY.plusHours(8));
        outageAlerts.dispatchDue(DAY.plusHours(15).plusMinutes(30));      // serveur arrêté pendant l'heure d'envoi
        SmsAlert late = alertsOf(f).stream().filter(x -> x.getOutageStart().equals(DAY.plusHours(15))).findFirst().orElseThrow();
        Assertions.assertEquals(SmsAlert.Status.CANCELLED, late.getStatus());
        Assertions.assertTrue(TEXTBEE_REQUESTS.isEmpty(), "pas de SMS pour une coupure déjà commencée");

        SmsAlert next = alertsOf(f).stream().filter(x -> x.getOutageStart().equals(DAY.plusHours(39))).findFirst().orElseThrow();
        outageAlerts.dispatchDue(DAY.plusHours(37));
        textBeeMessageStatus = "failed";
        outageAlerts.trackDeliveries(DAY.plusHours(37).plusMinutes(5));
        SmsAlert failed = smsAlertRepo.findById(next.getId()).orElseThrow();
        Assertions.assertEquals(SmsAlert.Status.FAILED, failed.getStatus());
        Assertions.assertEquals("failed", failed.getProviderStatus());
    }

    // ---------- SMS de test, historique, connexion ----------

    @Test
    void smsDeTestHistoriqueIsoleEtTestDeConnexion() throws Exception {
        MockHttpSession a = enableAlerts("a@exemple.tn", "motdepasseA1", "+21622345678");
        mvc.perform(get("/api/sms/status").session(a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.reachable").value(true))
                .andExpect(jsonPath("$.defaultDeviceName").value("Pixel AgriWatt"));
        mvc.perform(post("/api/account/alerts/test").session(a).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.kind").value("TEST"))
                .andExpect(jsonPath("$.smsBatchId").exists())
                .andExpect(jsonPath("$.recipient").value("+216 •••• 5678"));
        mvc.perform(post("/api/account/alerts/test").session(a).with(csrf())).andExpect(status().isBadRequest());   // 1 par minute
        mvc.perform(get("/api/account/alerts/history").session(a)).andExpect(jsonPath("$.length()").value(1));

        MockHttpSession b = login("b@exemple.tn", "motdepasseB1");
        mvc.perform(get("/api/account/alerts/history").session(b)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/account/alerts/test").session(b).with(csrf())).andExpect(status().isBadRequest());   // pas de numéro
    }

    @Test
    void suppressionDeFermeSupprimeSesAlertesEtCalculProgrammeLesAlertes() throws Exception {
        MockHttpSession a = enableAlerts("a@exemple.tn", "motdepasseA1", "+21622345678");
        Farm f = farmEntity(a, "Ferme A");
        outageAlerts.scheduleFromForecast(f, forecast(1.0, 15), DAY.plusHours(8));
        Assertions.assertFalse(alertsOf(f).isEmpty());
        mvc.perform(delete("/api/farms/" + f.getId()).session(a).with(csrf())).andExpect(status().isNoContent());
        Assertions.assertEquals(0, smsAlertRepo.count());
    }
}
