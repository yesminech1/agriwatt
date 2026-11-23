package tn.agriwatt.backend.dto;

/**
 * Paramètres d'un calcul. Pour POST /api/plan, seul farmId compte : Spring Boot relit TOUS les paramètres
 * de la ferme en base (propriétaire vérifié). Les autres champs restent dans le contrat pour compatibilité.
 */
public record PlanRequest(
        double lat, double lon, String crop,
        double areaM2, double panelAreaM2, double panelEfficiency,
        double pumpPowerKw, double pumpFlowM3h,
        double tankCapacityM3, double tankInitialM3,
        Integer outageStartHour, Integer outageEndHour,
        int days, Tariff tariff,
        Long farmId) {

    public record Tariff(double offPeak, double normal, double peak) {}

    /** Valeurs proposées par défaut dans le formulaire « Nouvelle ferme » (pas de position : elle est choisie sur la carte). */
    public static PlanRequest defaults() {
        return new PlanRequest(33.92, 8.13, "olive",
                20000, 24, 0.18,
                5.0, 10.0,
                30, 10,
                14, 19,
                2, new Tariff(0.20, 0.30, 0.45),
                null);
    }
}
