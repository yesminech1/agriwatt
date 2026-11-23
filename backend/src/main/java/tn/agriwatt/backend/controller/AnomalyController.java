package tn.agriwatt.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tn.agriwatt.backend.dto.AnomalyDtos.FarmAnomalies;
import tn.agriwatt.backend.dto.AnomalyDtos.ImportRequest;
import tn.agriwatt.backend.service.AnomalyService;

/** Mesures de pompe importées et anomalies détectées, par ferme de l'utilisateur connecté. */
@RestController
@RequestMapping("/api/farms/{farmId}/anomalies")
public class AnomalyController {

    private final AnomalyService anomalies;

    public AnomalyController(AnomalyService anomalies) {
        this.anomalies = anomalies;
    }

    @GetMapping
    public FarmAnomalies get(@PathVariable Long farmId, Authentication auth) {
        return anomalies.get(auth.getName(), farmId);
    }

    @PostMapping
    public FarmAnomalies importMeasurements(@PathVariable Long farmId, @RequestBody ImportRequest req, Authentication auth) {
        return anomalies.importMeasurements(auth.getName(), farmId, req);
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@PathVariable Long farmId, Authentication auth) {
        anomalies.clear(auth.getName(), farmId);
        return ResponseEntity.noContent().build();
    }
}
