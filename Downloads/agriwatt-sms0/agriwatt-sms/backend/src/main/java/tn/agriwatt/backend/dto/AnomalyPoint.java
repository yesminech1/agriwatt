// dto/AnomalyPoint.java
package tn.agriwatt.backend.dto;

public record AnomalyPoint(
        String timestamp, double powerKw, boolean isAnomaly,
        double score, String reason) {}