package tn.agriwatt.backend.service;

import org.springframework.stereotype.Service;

import tn.agriwatt.backend.dto.PlanRequest;

@Service
public class TariffService {

    public enum Band { OFF_PEAK, NORMAL, PEAK }

    /** HYPOTHÈSE de hackathon : creuses 0h-6h, pointe 17h-22h, normal le reste. */
    public Band bandOf(int hourOfDay) {
        if (hourOfDay >= 0 && hourOfDay < 6) return Band.OFF_PEAK;
        if (hourOfDay >= 17 && hourOfDay < 22) return Band.PEAK;
        return Band.NORMAL;
    }

    /** Prix du kWh réseau (dinars) pour une heure donnée. */
    public double priceOf(int hourOfDay, PlanRequest.Tariff t) {
        return switch (bandOf(hourOfDay)) {
            case OFF_PEAK -> t.offPeak();
            case PEAK -> t.peak();
            case NORMAL -> t.normal();
        };
    }

    /** Puissance de pompage : valeur fournie, sinon calculée par Q x H / (367 x rendement). */
    public double pumpPowerKw(PlanRequest r, double headMeters, double efficiency) {
        if (r.pumpPowerKw() > 0) return r.pumpPowerKw();
        return r.pumpFlowM3h() * headMeters / (367.0 * efficiency);
    }
}