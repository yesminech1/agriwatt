package tn.agriwatt.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import com.jayway.jsonpath.JsonPath;

import tn.agriwatt.backend.entity.PlanRun;

/** Historique dynamique : chaque calcul est enregistré avec sa ferme, isolé par ferme et par utilisateur. */
@SpringBootTest
class HistoryIntegrationTests extends FakePythonTestBase {

    @Test
    void chaqueCalculEstEnregistreAvecLaBonneFerme() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long fa = create(a, farm("Ferme A", TOZEUR[0], TOZEUR[1]));
        long fb = create(a, farm("Ferme B", SFAX[0], SFAX[1]));
        for (int i = 0; i < 3; i++) plan(a, fa);
        for (int i = 0; i < 2; i++) plan(a, fb);

        List<PlanRun> all = runRepo.findAll();
        Assertions.assertEquals(5, all.size());
        Assertions.assertEquals(3, runRepo.findByFarmId(fa).size());
        Assertions.assertEquals(2, runRepo.findByFarmId(fb).size());
        PlanRun r = runRepo.findByFarmId(fa).get(0);
        Assertions.assertEquals(TOZEUR[0], r.getLat(), 1e-9);
        Assertions.assertEquals("olive", r.getCrop());
        Assertions.assertNotNull(r.getSolarKwh());
        Assertions.assertNotNull(r.getWaterNeedM3());
        Assertions.assertNotNull(r.getPumpEnergyKwh());
        Assertions.assertNotNull(r.getSavingsTnd());
        Assertions.assertNotNull(r.getMinTankLevelM3());
        Assertions.assertEquals("openmeteo", r.getWeatherSource());
        Assertions.assertEquals(96, scheduleRepo.findByRunIdOrderByIdAsc(r.getId()).size());   // 48 h optimisées + 48 h de référence
    }

    @Test
    void historiqueDeLaFermeAneContientPasCeluiDeLaFermeB() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long fa = create(a, farm("Ferme A", TOZEUR[0], TOZEUR[1]));
        long fb = create(a, farm("Ferme B", SFAX[0], SFAX[1]));
        plan(a, fa); plan(a, fa); plan(a, fb);
        mvc.perform(get("/api/farms/" + fa + "/runs").session(a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].farmName").value("Ferme A"))
                .andExpect(jsonPath("$[1].farmId").value(fa));
        mvc.perform(get("/api/farms/" + fb + "/runs").session(a))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lat").value(SFAX[0]));
    }

    @Test
    void unAutreUtilisateurNePeutRienVoir() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long fa = create(a, farm("Ferme A", TOZEUR[0], TOZEUR[1]));
        long runId = ((Number) JsonPath.read(plan(a, fa), "$.runId")).longValue();
        MockHttpSession b = login("b@exemple.tn", "motdepasseB1");
        mvc.perform(get("/api/farms/" + fa + "/runs").session(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/runs/" + runId).session(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/runs").session(b)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/runs").session(a)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void detailDUnAncienCalculSansRecalcul() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long fa = create(a, farm("Ferme A", TOZEUR[0], TOZEUR[1]));
        String resp = plan(a, fa);
        long runId = ((Number) JsonPath.read(resp, "$.runId")).longValue();
        int calls = PYTHON_QUERIES.size();
        mvc.perform(get("/api/runs/" + runId).session(a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.id").value(runId))
                .andExpect(jsonPath("$.config.tankCapacityM3").value(30.0))
                .andExpect(jsonPath("$.kpis.solarSharePct").exists())
                .andExpect(jsonPath("$.schedule.length()").value(48))
                .andExpect(jsonPath("$.baselineSchedule.length()").value(48))
                .andExpect(jsonPath("$.schedule[10].solarKw").exists());
        Assertions.assertEquals(calls, PYTHON_QUERIES.size(), "consulter un ancien calcul ne relance rien");
    }

    @Test
    void filtreParPeriode() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long fa = create(a, farm("Ferme A", TOZEUR[0], TOZEUR[1]));
        plan(a, fa);
        PlanRun old = runRepo.findByFarmId(fa).get(0);
        old.setCreatedAt(old.getCreatedAt().minusDays(40));
        runRepo.save(old);
        plan(a, fa);
        String today = LocalDate.now().toString();
        mvc.perform(get("/api/farms/" + fa + "/runs?from=" + today + "&to=" + today).session(a))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/farms/" + fa + "/runs?to=" + LocalDate.now().minusDays(30)).session(a))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/farms/" + fa + "/runs").session(a)).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/farms/" + fa + "/runs?from=" + today + "&to=2020-01-01").session(a))
                .andExpect(status().isBadRequest());
    }

    @Test
    void historiqueConserveApresDeconnexionEtReconnexion() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long fa = create(a, farm("Ferme A", TOZEUR[0], TOZEUR[1]));
        plan(a, fa);
        mvc.perform(post("/api/auth/logout").session(a).with(csrf())).andExpect(status().isNoContent());
        MockHttpSession again = login("a@exemple.tn", "motdepasseA1");
        mvc.perform(get("/api/farms/" + fa + "/runs").session(again)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void calculSansFermeRefuseEtSuppressionDeFermeSupprimeSonHistorique() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        mvc.perform(post("/api/plan").session(a).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(1).replace(",\"farmId\":1", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
        long fa = create(a, farm("Ferme A", TOZEUR[0], TOZEUR[1]));
        plan(a, fa);
        mvc.perform(delete("/api/farms/" + fa).session(a).with(csrf())).andExpect(status().isNoContent());
        Assertions.assertEquals(0, runRepo.count());
        Assertions.assertEquals(0, scheduleRepo.count());
    }
}
