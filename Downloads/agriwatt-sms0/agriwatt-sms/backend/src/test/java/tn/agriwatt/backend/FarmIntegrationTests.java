package tn.agriwatt.backend;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

import com.jayway.jsonpath.JsonPath;

import tn.agriwatt.backend.entity.AppUser;
import tn.agriwatt.backend.repository.AppUserRepository;
import tn.agriwatt.backend.repository.FarmRepository;

/** Scénarios 1 à 5 de la gestion multi-fermes, avec deux utilisateurs réels (A et B). */
@SpringBootTest
class FarmIntegrationTests extends FakePythonTestBase {

    private static String farm(String name, String crop, double areaM2, double tankInitial) {
        return "{\"name\":\"" + name + "\",\"lat\":33.92,\"lon\":8.13,\"crop\":\"" + crop + "\","
                + "\"areaM2\":" + areaM2 + ",\"panelAreaM2\":24,\"panelEfficiency\":0.18,"
                + "\"pumpPowerKw\":5,\"pumpFlowM3h\":10,\"tankCapacityM3\":30,\"tankInitialM3\":" + tankInitial + ","
                + "\"outageStartHour\":14,\"outageEndHour\":19,\"days\":2,"
                + "\"offPeakTariff\":0.2,\"normalTariff\":0.3,\"peakTariff\":0.45}";
    }

    @Test
    void scenario1_AajouteDeuxFermesLesConsulteEtLesModifie() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long f1 = create(a, farm("Oliveraie Tozeur", "olive", 20000, 10));
        long f2 = create(a, farm("Palmeraie Nefta", "datte", 30000, 5));

        Assertions.assertEquals(2, farmRepo.count());                      // enregistrées en base
        mvc.perform(get("/api/farms").session(a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Oliveraie Tozeur"))
                .andExpect(jsonPath("$[0].user").doesNotExist());           // pas de fuite / boucle JSON

        mvc.perform(get("/api/farms/" + f2).session(a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crop").value("datte"))
                .andExpect(jsonPath("$.offPeakTariff").value(0.2));

        mvc.perform(put("/api/farms/" + f1).session(a).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(farm("Oliveraie Tozeur Nord", "olive", 25000, 12)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Oliveraie Tozeur Nord"))
                .andExpect(jsonPath("$.areaM2").value(25000.0));
    }

    @Test
    void scenario2_BneVoitQueSesFermes() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        create(a, farm("Ferme de A", "olive", 20000, 10));
        MockHttpSession b = login("b@exemple.tn", "motdepasseB1");
        create(b, farm("Ferme de B", "tomate", 5000, 3));

        mvc.perform(get("/api/farms").session(b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[*].name", hasItem("Ferme de B")))
                .andExpect(jsonPath("$[*].name", not(hasItem("Ferme de A"))));
    }

    @Test
    void scenario3_BnePeutNiLireNiModifierNiSupprimerUneFermeDeA() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long fermeA = create(a, farm("Ferme de A", "olive", 20000, 10));
        MockHttpSession b = login("b@exemple.tn", "motdepasseB1");

        mvc.perform(get("/api/farms/" + fermeA).session(b)).andExpect(status().isNotFound());
        mvc.perform(put("/api/farms/" + fermeA).session(b).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(farm("Piratée", "ble", 1, 0)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/farms/" + fermeA).session(b).with(csrf())).andExpect(status().isNotFound());

        // la ferme de A est intacte
        mvc.perform(get("/api/farms/" + fermeA).session(a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ferme de A"));

        // le propriétaire envoyé dans le JSON est ignoré : la ferme reste attachée à B, pas à A
        long fermeB = create(b, farm("Ferme B", "olive", 1000, 1).replace("{", "{\"userId\":1,\"user\":{\"id\":1},"));
        mvc.perform(get("/api/farms/" + fermeB).session(a)).andExpect(status().isNotFound());

        // anonyme : refusé
        mvc.perform(get("/api/farms")).andExpect(status().isUnauthorized());
    }

    @Test
    void scenario4_LaFermeSelectionneeAlimenteLaSimulation() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long f = create(a, farm("Petite ferme", "tomate", 5000, 4).replace("\"tankCapacityM3\":30", "\"tankCapacityM3\":12"));
        // le navigateur envoie farmId (+ des valeurs volontairement fausses : réservoir 99 m3) -> le serveur utilise la ferme
        String result = plan(a, f);
        java.util.List<Number> levels = JsonPath.read(result, "$.schedule[*].tankLevelM3");
        double max = levels.stream().mapToDouble(Number::doubleValue).max().orElse(0);
        Assertions.assertTrue(max <= 12.0 + 1e-9, "niveau max " + max + " > capacité de la ferme (12 m3)");
        Assertions.assertTrue(max > 0, "le réservoir doit être utilisé");
        Assertions.assertTrue(lastQuery().contains("crop=tomate") && lastQuery().contains("areaM2=5000.0"), lastQuery());
    }

    @Test
    void scenario5_SuppressionNeTouchePasLesAutresFermes() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long f1 = create(a, farm("À supprimer", "olive", 20000, 10));
        long f2 = create(a, farm("À garder", "datte", 30000, 5));

        mvc.perform(delete("/api/farms/" + f1).session(a).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/farms").session(a))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(f2))
                .andExpect(jsonPath("$[0].name").value("À garder"));
        mvc.perform(get("/api/farms/" + f1).session(a)).andExpect(status().isNotFound());
    }

    @Test
    void validationRenvoieLesErreursParChamp() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        String bad = farm("", "mais", -5, 10).replace("\"lat\":33.92", "\"lat\":95")
                .replace("\"panelEfficiency\":0.18", "\"panelEfficiency\":1.5").replace("\"days\":2", "\"days\":0");
        mvc.perform(post("/api/farms").session(a).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.lat").exists())
                .andExpect(jsonPath("$.errors.crop").exists())
                .andExpect(jsonPath("$.errors.areaM2").exists())
                .andExpect(jsonPath("$.errors.panelEfficiency").exists())
                .andExpect(jsonPath("$.errors.days").exists());

        // niveau initial > capacité, et coupure fin <= début
        mvc.perform(post("/api/farms").session(a).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(farm("Ferme", "olive", 20000, 40)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.tankInitialM3").exists());
        mvc.perform(post("/api/farms").session(a).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(farm("Ferme", "olive", 20000, 10).replace("\"outageEndHour\":19", "\"outageEndHour\":10")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.outageEndHour").exists());
        Assertions.assertEquals(0, farmRepo.count());
    }
}
