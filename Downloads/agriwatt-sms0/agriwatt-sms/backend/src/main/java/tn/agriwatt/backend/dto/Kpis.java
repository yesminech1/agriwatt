package tn.agriwatt.backend.dto;

public record Kpis(
        double solarSharePct, double costOptimizedTnd, double costBaselineTnd,
        double savingsTnd, double gridPeakKwhAvoided,
        int outageHoursCovered, int baselineOutageHoursCovered) {}