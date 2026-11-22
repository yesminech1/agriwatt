package tn.agriwatt.backend.dto;

import java.util.List;

public record PlanResponse(
        long runId,
        List<ScheduleItem> schedule,
        List<ScheduleItem> baselineSchedule,
        Kpis kpis,
        String weatherSource, Double lat, Double lon) {}   // d'où vient la météo et pour quelle position