package tn.agriwatt.backend.service;

import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import tn.agriwatt.backend.dto.DetectionDtos.PythonAnalysis;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appels au modèle de stress hydrique du service Python (FastAPI). Le navigateur ne parle jamais directement
 * à FastAPI : il passe par /api/detection/** (session et CSRF de Spring Security).
 */
@Service
public class PlantDetectionClient {

    private static final Logger log = LoggerFactory.getLogger(PlantDetectionClient.class);
    static final Set<String> PREDICTIONS = Set.of("SANS_STRESS", "STRESS_HYDRIQUE", "INCERTAIN");
    static final Set<String> CLASSES = Set.of("SANS_STRESS", "STRESS_HYDRIQUE");

    private final RestClient client;
    private final JsonMapper mapper;

    public PlantDetectionClient(@Value("${python.service.url}") String baseUrl,
                                @Value("${agriwatt.detection.read-timeout-ms:30000}") int readTimeoutMs,
                                JsonMapper mapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(readTimeoutMs);       // 1er appel : chargement du modèle en mémoire
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.mapper = mapper;
    }

    /** Envoie l'image au modèle et renvoie sa réponse, après contrôle de cohérence. */
    public PythonAnalysis analyze(byte[] image, String fileName, String contentType, String plantType) {
        // Requête multipart construite à la main (FormHttpMessageConverter) : aucune dépendance réactive nécessaire.
        // Nom de fichier ASCII côté Python : le nom d'origine reste dans la base AgriWatt.
        String partName = "image/png".equals(contentType) ? "image.png" : "image.jpg";
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(contentType));
        fileHeaders.setContentDispositionFormData("file", partName);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new ByteArrayResource(image) {
            @Override public String getFilename() { return partName; }
        }, fileHeaders));
        body.add("plantType", plantType);
        PythonAnalysis resp;
        try {
            resp = client.post().uri("/detection/analyze")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(PythonAnalysis.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("/detection/analyze en erreur HTTP {}", status);
            if (status == 400 || status == 413) {
                throw new IllegalArgumentException(detail(e, "Image refusée par le modèle."));
            }
            throw new ServiceUnavailableException(detail(e, "Analyse impossible pour le moment. Réessayez."));
        } catch (ResourceAccessException e) {
            log.warn("Service Python injoignable pour /detection/analyze ({})", e.getClass().getSimpleName());
            throw new ServiceUnavailableException(
                    "Le service d'intelligence artificielle (Python) est injoignable ou ne répond pas à temps. "
                            + "Vérifiez qu'il est lancé puis réessayez.");
        } catch (RestClientException e) {
            log.warn("Réponse illisible de /detection/analyze ({})", e.getClass().getSimpleName());
            throw new ServiceUnavailableException("Le modèle a renvoyé une réponse invalide. Réessayez.");
        }
        validate(resp);
        return resp;
    }

    /** État du modèle (disponibilité, plantes prises en charge, métriques mesurées sur le jeu de test). */
    public Map<String, Object> modelInfo() {
        try {
            Map<String, Object> info = client.get().uri("/detection/model").retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            if (info == null) throw new ServiceUnavailableException("Le service d'IA a renvoyé une réponse vide.");
            return info;
        } catch (RestClientResponseException e) {
            throw new ServiceUnavailableException(detail(e, "État du modèle indisponible."));
        } catch (ResourceAccessException e) {
            throw new ServiceUnavailableException(
                    "Le service d'intelligence artificielle (Python) est injoignable. Vérifiez qu'il est lancé.");
        } catch (RestClientException e) {
            throw new ServiceUnavailableException("Le service d'IA a renvoyé une réponse invalide.");
        }
    }

    /** Refuse toute réponse qui ne ressemble pas à une vraie sortie du modèle (rien n'est complété au hasard). */
    static void validate(PythonAnalysis r) {
        boolean ok = r != null && Boolean.TRUE.equals(r.success())
                && r.prediction() != null && PREDICTIONS.contains(r.prediction())
                && r.modelClass() != null && CLASSES.contains(r.modelClass())
                && r.confidence() != null && r.confidence() >= 0 && r.confidence() <= 1
                && r.probabilities() != null
                && inRange(r.probabilities().get("STRESS_HYDRIQUE"))
                && inRange(r.probabilities().get("SANS_STRESS"));
        if (!ok) throw new ServiceUnavailableException("Le modèle a renvoyé une réponse invalide. Réessayez.");
    }

    private static boolean inRange(Double v) {
        return v != null && v >= 0 && v <= 1;
    }

    private String detail(RestClientResponseException e, String fallbackMessage) {
        try {
            // JSON = UTF-8 : FastAPI n'indique pas de charset dans Content-Type
            String json = new String(e.getResponseBodyAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            PythonError err = mapper.readValue(json, PythonError.class);
            if (err != null && err.detail() != null && !err.detail().isBlank()) return err.detail();
        } catch (Exception ignored) {
            // corps non JSON ou liste d'erreurs de validation
        }
        return fallbackMessage;
    }

    record PythonError(String detail) {}
}