package tn.agriwatt.backend.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Détection du stress hydrique : réponse du service Python et vues renvoyées au navigateur. */
public final class DetectionDtos {
    private DetectionDtos() {}

    /** Réponse de POST /detection/analyze (FastAPI). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PythonAnalysis(Boolean success, String plantType, String plantLabel, String prediction,
                                 String predictionLabel, String modelClass, Double confidence,
                                 Map<String, Double> probabilities, Double uncertainThreshold,
                                 String interpretation, String recommendation, String modelVersion) {}

    /** Analyse enregistrée (détail). */
    public record AnalysisView(Long id, String plantType, String plantLabel, String prediction, String predictionLabel,
                               String modelClass, double confidence, double probStress, double probNoStress,
                               Double uncertainThreshold, String interpretation, String recommendation,
                               String modelVersion, String imageName, boolean hasImage, String imageUrl,
                               String createdAt) {}
}
