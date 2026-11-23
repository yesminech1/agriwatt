package tn.agriwatt.backend.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import tn.agriwatt.backend.dto.ForecastHour;
import tn.agriwatt.backend.dto.PlanRequest;

@Service
public class TankSimulator {

    /** Simule le réservoir heure par heure. pumpOn = décision pour chaque heure. */
    public List<SimHour> simulate(PlanRequest r, List<ForecastHour> hours, boolean[] pumpOn) {
        List<SimHour> result = new ArrayList<>();
        double level = Math.min(r.tankInitialM3(), r.tankCapacityM3());
        for (int i = 0; i < hours.size(); i++) {
            boolean want = i < pumpOn.length && pumpOn[i];
            SimHour s = step(r, hours.get(i), want, level);
            result.add(s);
            level = s.tankLevelM3();
        }
        return result;
    }

    /** Une seule heure : niveau de départ + décision -> ce qui se passe réellement. */
    public SimHour step(PlanRequest r, ForecastHour f, boolean want, double level) {
        double pMax = Math.max(r.pumpPowerKw(), 0.001);
        int hod = LocalDateTime.parse(f.hour()).getHour();
        boolean outage = isOutage(r, f, hod);

        double kw = 0, solar = 0, grid = 0;
        if (want) {
            if (outage) {                       // pas de réseau : solaire seul, puissance réduite
                kw = Math.min(r.pumpPowerKw(), f.pvKw());
                solar = kw;
            } else {                            // pleine puissance : solaire d'abord, réseau en complément
                kw = r.pumpPowerKw();
                solar = Math.min(kw, f.pvKw());
                grid = kw - solar;
            }
        }
        boolean on = kw > 0.05;
        if (!on) { kw = 0; solar = 0; grid = 0; }

        double pumped = r.pumpFlowM3h() * kw / pMax;
        double need = f.waterNeedM3();
        double delivered = Math.min(need, level + pumped);
        double next = Math.max(0, Math.min(r.tankCapacityM3(), level + pumped - delivered));

        String source = !on ? "none" : (solar >= 0.5 * kw ? "solar" : "grid");
        return new SimHour(f.hour(), hod, outage, need, on, source, kw,
                solar, grid, pumped, delivered, next, f.pvKw(), f.tempC());
    }

    public int coveredOutageHours(List<SimHour> sim) {
        return (int) sim.stream().filter(SimHour::covered).count();
    }

    /** Coupure = plage demandée par l'utilisateur, sinon risque de coupure maximal chez A. */
    private boolean isOutage(PlanRequest r, ForecastHour f, int hod) {
        if (r.outageStartHour() != null && r.outageEndHour() != null) {
            int s = r.outageStartHour(), e = r.outageEndHour();
            return s <= e ? (hod >= s && hod < e) : (hod >= s || hod < e);
        }
        return f.outageRisk() >= 0.99;
    }
}