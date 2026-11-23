package tn.agriwatt.backend.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tn.agriwatt.backend.dto.AnomalyDtos.PumpPoint;
import tn.agriwatt.backend.dto.AnomalyResponse;
import tn.agriwatt.backend.dto.ForecastResponse;
import tn.agriwatt.backend.dto.PlanRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appels au service Python (FastAPI). Plus aucun fichier de secours fictif : si la météo réelle ou l'analyse
 * est indisponible, une ServiceUnavailableException (HTTP 503) explique pourquoi.
 */
@Service
public class PythonClient {

    private static final Logger log = LoggerFactory.getLogger(PythonClient.class);

    private final RestClient client;
    private final JsonMapper mapper;

    public PythonClient(@Value("${python.service.url}") String baseUrl, JsonMapper mapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(15000);     // Open-Meteo peut prendre quelques secondes
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.mapper = mapper;
    }

    /** Prévision horaire RÉELLE (Open-Meteo) à la position de la ferme. */
    public ForecastResponse forecast(PlanRequest r) {
        try {
            ForecastResponse resp = client.get()
                    .uri(b -> {
                        b.path("/forecast")
                         .queryParam("lat", r.lat())
                         .queryParam("lon", r.lon())
                         .queryParam("crop", r.crop())
                         .queryParam("areaM2", r.areaM2())
                         .queryParam("panelAreaM2", r.panelAreaM2())
                         .queryParam("panelEfficiency", r.panelEfficiency())
                         .queryParam("days", r.days());
                        if (PythonClientRules.hasOutage(r)) {
                            b.queryParam("forceOutageStart", r.outageStartHour())
                             .queryParam("forceOutageEnd", r.outageEndHour());
                        }
                        return b.build();
                    })
                    .retrieve()
                    .body(ForecastResponse.class);
            if (resp == null || resp.hours() == null || resp.hours().isEmpty()) {
                throw new ServiceUnavailableException("Le service de prévision a renvoyé une réponse vide. Réessayez.");
            }
            return resp;
        } catch (RestClientResponseException e) {
            log.warn("/forecast en erreur HTTP {}", e.getStatusCode().value());
            throw new ServiceUnavailableException(detail(e,
                    "Prévisions météo indisponibles pour cette position. Réessayez dans quelques instants."));
        } catch (ResourceAccessException e) {
            log.warn("Service Python injoignable pour /forecast ({})", e.getMessage());
            throw new ServiceUnavailableException(
                    "Le service de prévision météo (Python) est injoignable. Vérifiez qu'il est lancé puis réessayez.");
        }
    }

    /** Analyse Isolation Forest de mesures de pompe RÉELLES importées par l'utilisateur. */
    public AnomalyResponse detect(List<PumpPoint> points) {
        try {
            AnomalyResponse resp = client.post().uri("/anomalies/detect")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("points", points))
                    .retrieve()
                    .body(AnomalyResponse.class);
            if (resp == null || resp.points() == null) {
                throw new ServiceUnavailableException("Le service d'analyse a renvoyé une réponse vide. Réessayez.");
            }
            return resp;
        } catch (RestClientResponseException e) {
            throw new ServiceUnavailableException(detail(e, "Analyse des mesures impossible pour le moment. Réessayez."));
        } catch (ResourceAccessException e) {
            throw new ServiceUnavailableException(
                    "Le service d'analyse (Python) est injoignable. Vérifiez qu'il est lancé puis réessayez.");
        }
    }

    /** Message « detail » renvoyé par FastAPI, sinon message par défaut. */
    private String detail(RestClientResponseException e, String fallbackMessage) {
        try {
            PythonError err = mapper.readValue(e.getResponseBodyAsString(), PythonError.class);
            if (err != null && err.detail() != null && !err.detail().isBlank()) return err.detail();
        } catch (Exception ignored) {
            // corps non textuel (ex. liste d'erreurs de validation)
        }
        return fallbackMessage;
    }

    record PythonError(String detail) {}
}
