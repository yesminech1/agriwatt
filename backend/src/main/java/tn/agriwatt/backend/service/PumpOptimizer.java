package tn.agriwatt.backend.service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;

import tn.agriwatt.backend.dto.ForecastHour;
import tn.agriwatt.backend.dto.PlanRequest;

/**
 * Optimiseur par programmation dynamique.
 * Etat = (heure, niveau du réservoir arrondi à 0,25 m3). Décision = pompe ON/OFF.
 * Coût = réseau (kWh x tarif) + pénalité d'eau non livrée (x3 pendant la coupure).
 */
@Service
public class PumpOptimizer {

    private static final double STEP = 0.25;                 // résolution du réservoir (m3)
    private static final double SHORTAGE_TND_PER_M3 = 2.0;   // pénalité eau manquante
    private static final double OUTAGE_FACTOR = 3.0;         // la coupure est prioritaire
    private static final double WASTE_TND_PER_KWH = 0.001;   // évite de pomper pour rien

    private final TankSimulator simulator;
    private final TariffService tariffs;

    public PumpOptimizer(TankSimulator simulator, TariffService tariffs) {
        this.simulator = simulator;
        this.tariffs = tariffs;
    }

    public boolean[] plan(PlanRequest r, List<ForecastHour> hours) {
        int n = hours.size();
        int levels = (int) Math.round(r.tankCapacityM3() / STEP);

        double[][] cost = new double[n + 1][levels + 1];
        int[][] from = new int[n + 1][levels + 1];
        boolean[][] decision = new boolean[n + 1][levels + 1];
        for (double[] row : cost) Arrays.fill(row, Double.MAX_VALUE);

        cost[0][toIndex(Math.min(r.tankInitialM3(), r.tankCapacityM3()), levels)] = 0;

        for (int i = 0; i < n; i++) {
            ForecastHour f = hours.get(i);
            int hod = LocalDateTime.parse(f.hour()).getHour();
            double price = tariffs.priceOf(hod, r.tariff());

            for (int l = 0; l <= levels; l++) {
                if (cost[i][l] == Double.MAX_VALUE) continue;
                double level = l * STEP;

                for (int d = 0; d < 2; d++) {
                    boolean want = (d == 1);
                    SimHour s = simulator.step(r, f, want, level);

                    double shortage = Math.max(0, s.needM3() - s.waterDeliveredM3());
                    double factor = s.outage() ? OUTAGE_FACTOR : 1.0;
                    double c = cost[i][l]
                            + s.gridKwh() * price
                            + WASTE_TND_PER_KWH * (s.solarKwh() + s.gridKwh())
                            + SHORTAGE_TND_PER_M3 * factor * shortage;

                    int nl = toIndex(s.tankLevelM3(), levels);
                    if (c < cost[i + 1][nl]) {
                        cost[i + 1][nl] = c;
                        from[i + 1][nl] = l;
                        decision[i + 1][nl] = want;
                    }
                }
            }
        }

        // meilleur état final, puis on remonte le chemin
        int best = 0;
        for (int l = 1; l <= levels; l++) {
            if (cost[n][l] < cost[n][best]) best = l;
        }
        boolean[] plan = new boolean[n];
        for (int i = n; i > 0; i--) {
            plan[i - 1] = decision[i][best];
            best = from[i][best];
        }
        return plan;
    }

    /** Arrondi vers le bas : marge de sécurité (on ne surestime jamais l'eau disponible). */
    private int toIndex(double level, int levels) {
        int idx = (int) Math.floor(level / STEP + 1e-9);
        return Math.max(0, Math.min(levels, idx));
    }
}