package tn.agriwatt.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import tn.agriwatt.backend.dto.AnomalyResponse;
import tn.agriwatt.backend.dto.ForecastResponse;
import tn.agriwatt.backend.dto.PlanRequest;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PythonClient {

    private static final Logger log = LoggerFactory.getLogger(PythonClient.class);

    private final RestClient client;
    private final JsonMapper mapper;

    public PythonClient(@Value("${python.service.url}") String baseUrl, JsonMapper mapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);   // Python éteint : bascule en 2 s
        factory.setReadTimeout(10000);     // recommandé par A (Open-Meteo peut prendre 5 s)
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.mapper = mapper;
    }

    /** Prévision horaire. Si Python est indisponible : fichier de secours. */
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
                        if (r.outageStartHour() != null && r.outageEndHour() != null) {
                            b.queryParam("forceOutageStart", r.outageStartHour())
                             .queryParam("forceOutageEnd", r.outageEndHour());
                        }
                        if (r.scenario() != null && !r.scenario().isBlank()) {
                            b.queryParam("scenario", r.scenario());
                        }
                        return b.build();
                    })
                    .retrieve()
                    .body(ForecastResponse.class);
            if (resp != null && resp.hours() != null && !resp.hours().isEmpty()) {
                return resp;
            }
            log.warn("Réponse /forecast vide, plan B activé");
        } catch (Exception e) {
            log.warn("Service Python indisponible pour /forecast ({}), plan B activé", e.getMessage());
        }
        return loadFallback("forecast-fallback.json", ForecastResponse.class);
    }

    /** Anomalies de la pompe (données simulées par A). */
    public AnomalyResponse anomalies(int days) {
        try {
            AnomalyResponse resp = client.get()
                    .uri(b -> b.path("/anomalies/demo").queryParam("days", days).build())
                    .retrieve()
                    .body(AnomalyResponse.class);
            if (resp != null && resp.points() != null) {
                return resp;
            }
        } catch (Exception e) {
            log.warn("Service Python indisponible pour /anomalies/demo ({}), plan B activé", e.getMessage());
        }
        return loadFallback("anomalies-fallback.json", AnomalyResponse.class);
    }

    private <T> T loadFallback(String file, Class<T> type) {
        try (var in = new ClassPathResource(file).getInputStream()) {
            return mapper.readValue(in, type);
        } catch (Exception e) {
            throw new IllegalStateException("Fichier de secours introuvable ou invalide : " + file, e);
        }
    }
}