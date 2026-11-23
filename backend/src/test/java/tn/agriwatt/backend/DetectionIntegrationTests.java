package tn.agriwatt.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import com.jayway.jsonpath.JsonPath;

import tn.agriwatt.backend.entity.PlantAnalysis;

/**
 * Détection du stress hydrique : Spring Boot relaie l'image au (faux) modèle Python, enregistre la réponse
 * telle quelle et l'historique reste propre à chaque utilisateur. Les réponses du modèle sont des données de TEST.
 */
@SpringBootTest
class DetectionIntegrationTests extends FakePythonTestBase {

    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 2, 3};
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    static final Path STORAGE = Path.of("target/test-uploads/plant-analyses").toAbsolutePath();

    long analyze(MockHttpSession s, MockMultipartFile file) throws Exception {
        String body = mvc.perform(multipart("/api/detection/analyze").file(file).param("plantType", "olivier")
                        .session(s).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    void analyseEnregistreLaReponseDuModeleEtLImage() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        mvc.perform(multipart("/api/detection/analyze").file(new MockMultipartFile("file", "olivier 1.jpg", "image/jpeg", JPEG))
                        .param("plantType", "olivier").session(a).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prediction").value("STRESS_HYDRIQUE"))
                .andExpect(jsonPath("$.predictionLabel").value("Signes compatibles avec un stress hydrique"))
                .andExpect(jsonPath("$.confidence").value(0.8312))
                .andExpect(jsonPath("$.probStress").value(0.8312))
                .andExpect(jsonPath("$.probNoStress").value(0.1688))
                .andExpect(jsonPath("$.plantLabel").value("Olivier"))
                .andExpect(jsonPath("$.recommendation").value("Vérifiez l'humidité du sol."))
                .andExpect(jsonPath("$.hasImage").value(true))
                .andExpect(jsonPath("$.imageName").value("olivier 1.jpg"));

        Assertions.assertEquals(1, DETECTION_REQUESTS.size());
        String sent = DETECTION_REQUESTS.get(0);
        Assertions.assertTrue(sent.contains("name=\"plantType\"") && sent.contains("olivier"));
        Assertions.assertTrue(sent.contains("name=\"file\"") && sent.contains("JFIF"));   // les octets de l'image

        PlantAnalysis saved = plantAnalysisRepo.findAll().get(0);
        Assertions.assertEquals("a@exemple.tn", users.findById(saved.getUser().getId()).orElseThrow().getEmail());
        Assertions.assertTrue(Files.isRegularFile(STORAGE.resolve(saved.getImageRef())));

        mvc.perform(get("/api/detection/analyses").session(a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].prediction").value("STRESS_HYDRIQUE"));
        mvc.perform(get("/api/detection/analyses/" + saved.getId() + "/image").session(a))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(JPEG));
    }

    @Test
    void historiqueStrictementPersonnel() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        MockHttpSession b = login("b@exemple.tn", "motdepasseB1");
        long id = analyze(a, new MockMultipartFile("file", "a.png", "image/png", PNG));

        mvc.perform(get("/api/detection/analyses").session(b)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/detection/analyses/" + id).session(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/detection/analyses/" + id + "/image").session(b)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/detection/analyses/" + id).session(b).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/detection/analyses/" + id).session(a)).andExpect(status().isOk());
        mvc.perform(get("/api/detection/analyses")).andExpect(status().isUnauthorized());
    }

    @Test
    void suppressionEffaceLAnalyseEtLImage() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        long id = analyze(a, new MockMultipartFile("file", "a.jpg", "image/jpeg", JPEG));
        Path file = STORAGE.resolve(plantAnalysisRepo.findById(id).orElseThrow().getImageRef());
        mvc.perform(delete("/api/detection/analyses/" + id).session(a).with(csrf())).andExpect(status().isNoContent());
        Assertions.assertTrue(plantAnalysisRepo.findAll().isEmpty());
        Assertions.assertFalse(Files.exists(file));
    }

    @Test
    void fichiersInvalidesRefusesSansAppelerLeModele() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        MockMultipartFile[] bad = {
                new MockMultipartFile("file", "faux.jpg", "image/jpeg", "bonjour".getBytes()),      // pas une image
                new MockMultipartFile("file", "a.gif", "image/gif", JPEG),                          // extension refusée
                new MockMultipartFile("file", "vide.jpg", "image/jpeg", new byte[0]),
        };
        for (MockMultipartFile f : bad) {
            mvc.perform(multipart("/api/detection/analyze").file(f).param("plantType", "olivier").session(a).with(csrf()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        }
        mvc.perform(multipart("/api/detection/analyze").file(new MockMultipartFile("file", "a.jpg", "image/jpeg", JPEG))
                        .param("plantType", "tomate").session(a).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Plante non prise en charge : le modèle ne reconnaît que l'olivier."));
        mvc.perform(multipart("/api/detection/analyze").param("plantType", "olivier").session(a).with(csrf()))
                .andExpect(status().isBadRequest());
        Assertions.assertTrue(DETECTION_REQUESTS.isEmpty());
        Assertions.assertTrue(plantAnalysisRepo.findAll().isEmpty());
    }

    @Test
    void erreursDuModeleRelayeesSansRienEnregistrer() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        MockMultipartFile img = new MockMultipartFile("file", "a.jpg", "image/jpeg", JPEG);

        detectionStatus = 503;
        detectionBody = "{\"detail\":\"Le modèle de détection n'est pas encore entraîné.\"}";
        mvc.perform(multipart("/api/detection/analyze").file(img).param("plantType", "olivier").session(a).with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Le modèle de détection n'est pas encore entraîné."));

        detectionStatus = 400;
        detectionBody = "{\"detail\":\"Image trop petite (au moins 64 × 64 pixels).\"}";
        mvc.perform(multipart("/api/detection/analyze").file(img).param("plantType", "olivier").session(a).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Image trop petite (au moins 64 × 64 pixels)."));

        detectionStatus = 200;                                   // réponse incohérente : refusée, pas complétée
        detectionBody = DETECTION_OK.replace("\"prediction\":\"STRESS_HYDRIQUE\"", "\"prediction\":\"PEUT_ETRE\"");
        mvc.perform(multipart("/api/detection/analyze").file(img).param("plantType", "olivier").session(a).with(csrf()))
                .andExpect(status().isServiceUnavailable());
        detectionBody = "pas du json";
        mvc.perform(multipart("/api/detection/analyze").file(img).param("plantType", "olivier").session(a).with(csrf()))
                .andExpect(status().isServiceUnavailable());

        Assertions.assertTrue(plantAnalysisRepo.findAll().isEmpty());
    }

    @Test
    void etatDuModeleRelaye() throws Exception {
        MockHttpSession a = login("a@exemple.tn", "motdepasseA1");
        mvc.perform(get("/api/detection/model").session(a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.plants[0].label").value("Olivier"))
                .andExpect(jsonPath("$.metrics.test.accuracy").value(0.8));
    }
}
