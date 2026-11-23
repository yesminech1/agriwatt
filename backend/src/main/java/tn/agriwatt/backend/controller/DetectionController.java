package tn.agriwatt.backend.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import tn.agriwatt.backend.dto.DetectionDtos.AnalysisView;
import tn.agriwatt.backend.service.PlantDetectionClient;
import tn.agriwatt.backend.service.PlantDetectionService;
import tn.agriwatt.backend.service.PlantDetectionService.AnalysisNotFoundException;
import tn.agriwatt.backend.service.PlantDetectionService.StoredImage;
import tn.agriwatt.backend.service.ServiceUnavailableException;

/** Détection du stress hydrique des plantes (photo -> modèle IA) et historique de l'utilisateur connecté. */
@RestController
@RequestMapping("/api/detection")
public class DetectionController {

    private final PlantDetectionService detection;
    private final PlantDetectionClient model;

    public DetectionController(PlantDetectionService detection, PlantDetectionClient model) {
        this.detection = detection;
        this.model = model;
    }

    /** Disponibilité du modèle, plantes prises en charge et métriques mesurées sur le jeu de test. */
    @GetMapping("/model")
    public Map<String, Object> modelInfo() {
        return model.modelInfo();
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AnalysisView analyze(@RequestParam("file") MultipartFile file,
                                @RequestParam(value = "plantType", defaultValue = "olivier") String plantType,
                                Authentication auth) {
        return detection.analyze(auth.getName(), file, plantType);
    }

    @GetMapping("/analyses")
    public List<AnalysisView> list(Authentication auth) {
        return detection.list(auth.getName());
    }

    @GetMapping("/analyses/{id}")
    public AnalysisView get(@PathVariable Long id, Authentication auth) {
        return detection.get(auth.getName(), id);
    }

    @GetMapping("/analyses/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id, Authentication auth) {
        StoredImage img = detection.image(auth.getName(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(img.bytes());
    }

    @DeleteMapping("/analyses/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication auth) {
        detection.delete(auth.getName(), id);
        return ResponseEntity.noContent().build();
    }

    // ---------- erreurs au format {"message": "..."} ----------
    @ExceptionHandler(AnalysisNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(AnalysisNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<Map<String, String>> missingFile(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", "Sélectionnez une image à analyser."));
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable(ServiceUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", e.getMessage()));
    }
}
