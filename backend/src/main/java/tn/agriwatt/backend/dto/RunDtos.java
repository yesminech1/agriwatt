package tn.agriwatt.backend.dto;

import java.util.List;

/** Historique des calculs (page Historique). */
public final class RunDtos {
    private RunDtos() {}

    public record RunSummary(
            long id, Long farmId, String farmName, String createdAt,
            Double lat, Double lon, String crop, Double areaM2, Integer days,
            String forecastStart, String forecastEnd, String weatherSource,
            Double solarKwh, Double gridKwh, Double pumpEnergyKwh,
            Double waterNeedM3, Double waterDeliveredM3,
            Double savingsTnd, Double costOptimizedTnd, Double costBaselineTnd,
            Double solarSharePct, Double gridPeakKwhAvoided,
            Integer outageHoursCovered, Integer baselineOutageHoursCovered,
            Double minTankLevelM3, Double finalTankLevelM3) {}

    /** Détail d'un ancien calcul, relu en base (aucun recalcul). */
    public record RunDetail(RunSummary summary, PlanRequest config, Kpis kpis,
                            List<ScheduleItem> schedule, List<ScheduleItem> baselineSchedule) {}
}
