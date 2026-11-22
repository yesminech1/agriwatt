package tn.agriwatt.backend.dto;

import java.util.List;

/** Mesures de pompe importées (CSV converti par le front) et résultats enregistrés pour une ferme. */
public final class AnomalyDtos {
    private AnomalyDtos() {}

    public record PumpPoint(String timestamp, Double powerKw) {}
    public record ImportRequest(String fileName, List<PumpPoint> points) {}
    public record FarmAnomalies(Long farmId, String fileName, String importedAt,
                                int count, int anomalyCount, List<AnomalyPoint> points) {}
}
