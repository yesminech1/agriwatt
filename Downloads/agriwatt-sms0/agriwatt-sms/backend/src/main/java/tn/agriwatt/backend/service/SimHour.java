package tn.agriwatt.backend.service;

import tn.agriwatt.backend.dto.ScheduleItem;

/** Résultat détaillé d'une heure simulée (plus riche que ScheduleItem, utile pour les KPIs). */
public record SimHour(
        String hour, int hourOfDay, boolean outage, double needM3,
        boolean pumpOn, String source, double pumpKw,
        double solarKwh, double gridKwh,
        double waterPumpedM3, double waterDeliveredM3, double tankLevelM3,
        double pvKw, double tempC) {

    /** Heure de coupure où le besoin en eau est satisfait. */
    public boolean covered() {
        return outage && waterDeliveredM3 >= needM3 - 1e-6;
    }

    public ScheduleItem toItem() {
        return new ScheduleItem(hour, pumpOn, source, r2(pumpKw),
                r2(waterPumpedM3), r2(waterDeliveredM3), r2(tankLevelM3), outage,
                r2(pvKw), r2(tempC));
    }

    private static double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}