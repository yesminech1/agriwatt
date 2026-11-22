package tn.agriwatt.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

/** Anomalies : mesures réelles importées par ferme, analysées puis enregistrées, isolées par utilisateur. */
@SpringBootTest
class AnomalyIntegrationTests extends FakePythonTestBase {

    /** 40 mesures horaires (données de TEST) dont 2 anormales. */
    static String measures(String fileName) {
        StringBuilder sb = new StringBuilder("{\"fileName\":\"" + fileName + "\",\"points\":[");
        for (int i = 0; i < 40; i++) {
            double p = i == 12 ? 6.9 : i == 20 ? 1.1 : 5.0;
            sb.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT,
                    "{\"timestamp\":\"2026-07-%02dT%02d:00\",\"powerKw\":%.1f}", 10 + i / 24, i % 24, p));
        }
        return sb.append("]}").toString();
    }

    @Test
    void importAnalyseEtEnregistrementParFerme() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long fa = create(a, farm("Ferme A", TOZEUR[0], TOZEUR[1]));
        mvc.perform(get("/api/farms/" + fa + "/anomalies").session(a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));   // rien tant que rien n'est importé

        mvc.perform(post("/api/farms/" + fa + "/anomalies").session(a).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(measures("pompe-juillet.csv")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(40))
                .andExpect(jsonPath("$.anomalyCount").value(2))
                .andExpect(jsonPath("$.fileName").value("pompe-juillet.csv"));
        Assertions.assertEquals(40, anomalyRepo.findByFarmIdOrderByTimestampAsc(fa).size());

        // un nouvel import remplace le précédent
        mvc.perform(post("/api/farms/" + fa + "/anomalies").session(a).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(measures("pompe-aout.csv"))).andExpect(status().isOk());
        Assertions.assertEquals(40, anomalyRepo.findByFarmIdOrderByTimestampAsc(fa).size());

        mvc.perform(delete("/api/farms/" + fa + "/anomalies").session(a).with(csrf())).andExpect(status().isNoContent());
        Assertions.assertEquals(0, anomalyRepo.count());
    }

    @Test
    void autreUtilisateurEtDonneesInvalidesRefuses() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long fa = create(a, farm("Ferme A", TOZEUR[0], TOZEUR[1]));
        MockHttpSession b = login("b@exemple.tn", "motdepasseB1");
        mvc.perform(get("/api/farms/" + fa + "/anomalies").session(b)).andExpect(status().isNotFound());
        mvc.perform(post("/api/farms/" + fa + "/anomalies").session(b).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(measures("x.csv"))).andExpect(status().isNotFound());

        mvc.perform(post("/api/farms/" + fa + "/anomalies").session(a).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"points\":[{\"timestamp\":\"2026-07-10T08:00\",\"powerKw\":5}]}"))
                .andExpect(status().isBadRequest());                                    // moins de 20 mesures
        mvc.perform(post("/api/farms/" + fa + "/anomalies").session(a).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(measures("x.csv").replace("2026-07-10T05:00", "10/07/2026")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Ligne 6")));
        Assertions.assertEquals(0, anomalyRepo.count());
    }
}
