package tn.agriwatt.backend.dto;

public record PlanRequest(
        double lat, double lon, String crop,
        double areaM2, double panelAreaM2, double panelEfficiency,
        double pumpPowerKw, double pumpFlowM3h,
        double tankCapacityM3, double tankInitialM3,
        Integer outageStartHour, Integer outageEndHour,
        int days, Tariff tariff,
        String scenario) {

    public record Tariff(double offPeak, double normal, double peak) {}

    /** Scénario de démo : 2 ha d'oliviers à Tozeur, canicule + coupure 14h-19h. */
    public static PlanRequest defaults() {
        return new PlanRequest(33.92, 8.13, "olive",
                20000, 24, 0.18,
                5.0, 10.0,
                30, 10,
                14, 19,
                2, new Tariff(0.20, 0.30, 0.45),
                "canicule");
    }
}