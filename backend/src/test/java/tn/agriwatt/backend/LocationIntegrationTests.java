package tn.agriwatt.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import com.jayway.jsonpath.JsonPath;

import tn.agriwatt.backend.service.TunisiaGeo;

/** Carte : les coordonnées ENREGISTRÉES de la ferme sont celles transmises au service Python (météo réelle). */
@SpringBootTest
class LocationIntegrationTests extends FakePythonTestBase {

    @Test
    void test1_TozeurEnregistreeEtTransmiseAuServicePython() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long id = create(a, farm("Oliveraie Tozeur", TOZEUR[0], TOZEUR[1]));
        mvc.perform(get("/api/farms/" + id).session(a))
                .andExpect(jsonPath("$.lat").value(TOZEUR[0])).andExpect(jsonPath("$.lon").value(TOZEUR[1]));
        String resp = plan(a, id);
        Assertions.assertTrue(lastQuery().contains("lat=33.9197") && lastQuery().contains("lon=8.1335"), lastQuery());
        Assertions.assertFalse(lastQuery().contains("scenario"), "aucun scénario : météo réelle uniquement");
        Assertions.assertTrue(lastQuery().contains("areaM2=20000.0") && lastQuery().contains("crop=olive"),
                "paramètres de la ferme en base, pas ceux du navigateur : " + lastQuery());
        Assertions.assertEquals("openmeteo", JsonPath.read(resp, "$.weatherSource"));
    }

    @Test
    void test2_DeplacementVersSfaxChangeLesCoordonneesUtilisees() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long id = create(a, farm("Ma ferme", TOZEUR[0], TOZEUR[1]));
        plan(a, id);
        String avant = lastQuery();
        mvc.perform(put("/api/farms/" + id).session(a).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(farm("Ma ferme", SFAX[0], SFAX[1]))).andExpect(status().isOk());
        plan(a, id);
        Assertions.assertTrue(lastQuery().contains("lat=34.7406") && lastQuery().contains("lon=10.7603"), lastQuery());
        Assertions.assertNotEquals(avant, lastQuery());
    }

    @Test
    void test3_DeuxFermesSelectionneesAlternativement() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long tozeur = create(a, farm("Tozeur", TOZEUR[0], TOZEUR[1]));
        long sfax = create(a, farm("Sfax", SFAX[0], SFAX[1]));
        for (int i = 0; i < 2; i++) {
            plan(a, tozeur);
            Assertions.assertTrue(lastQuery().contains("lat=33.9197"), lastQuery());
            plan(a, sfax);
            Assertions.assertTrue(lastQuery().contains("lat=34.7406"), lastQuery());
        }
    }

    @Test
    void test4_ModificationDeLaPositionEnregistreeEnBase() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long id = create(a, farm("Ferme", TOZEUR[0], TOZEUR[1]));
        mvc.perform(put("/api/farms/" + id).session(a).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(farm("Ferme", 33.95, 8.20))).andExpect(status().isOk());
        var saved = farmRepo.findById(id).orElseThrow();
        Assertions.assertEquals(33.95, saved.getLat(), 1e-9);
        Assertions.assertEquals(8.20, saved.getLon(), 1e-9);
    }

    @Test
    void test5_BnePeutNiDeplacerNiSimulerLaFermeDeA() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long id = create(a, farm("Ferme de A", TOZEUR[0], TOZEUR[1]));
        MockHttpSession b = login("b@exemple.tn", "motdepasseB1");
        mvc.perform(put("/api/farms/" + id).session(b).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(farm("Déplacée", SFAX[0], SFAX[1]))).andExpect(status().isNotFound());
        Assertions.assertEquals(TOZEUR[0], farmRepo.findById(id).orElseThrow().getLat(), 1e-9);
        int before = PYTHON_QUERIES.size();
        mvc.perform(post("/api/plan").session(b).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(planBody(id)))
                .andExpect(status().isNotFound());
        Assertions.assertEquals(before, PYTHON_QUERIES.size(), "aucun appel Python avec la ferme d'un autre compte");
    }

    @Test
    void test6_PositionManquanteOuHorsTunisieRefusee() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        String[][] invalides = {{"null", "null"}, {"48.85", "2.35"}, {"37.60", "11.60"}, {"35.40", "7.80"}, {"31.90", "11.50"}};
        for (String[] p : invalides) {
            mvc.perform(post("/api/farms").session(a).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(farm("Ferme", p[0], p[1])))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.lat").exists());
        }
        Assertions.assertEquals(0, farmRepo.count());
    }

    @Test
    void contourTunisieAccepteLesVillesPrincipales() {
        double[][] villes = {{36.80, 10.18}, {34.74, 10.76}, {33.92, 8.13}, {35.68, 10.10}, {33.88, 10.10},
                {37.27, 9.87}, {36.95, 8.76}, {33.87, 10.86}, {34.75, 11.20}, {32.93, 10.45}, {30.25, 9.55},
                {36.85, 11.09}, {35.17, 8.84}, {33.87, 7.88}};
        for (double[] v : villes) Assertions.assertTrue(TunisiaGeo.contains(v[0], v[1]), v[0] + "," + v[1]);
    }
}
