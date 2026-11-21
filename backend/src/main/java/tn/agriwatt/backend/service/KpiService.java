package tn.agriwatt.backend.service;

import java.util.List;

import org.springframework.stereotype.Service;

import tn.agriwatt.backend.dto.Kpis;
import tn.agriwatt.backend.dto.PlanRequest;

@Service
public class KpiService {

    private final TankSimulator simulator;
    private final TariffService tariffs;

    public KpiService(TankSimulator simulator, TariffService tariffs) {
        this.simulator = simulator;
        this.tariffs = tariffs;
    }

    public Kpis compute(PlanRequest r, List<SimHour> opt, List<SimHour> base) {
        // énergie nécessaire pour pomper 1 m3 (kWh/m3), sert à valoriser l'eau manquante
        double kwhPerM3 = r.pumpFlowM3h() > 0 ? r.pumpPowerKw() / r.pumpFlowM3h() : 0.5;
        double shortagePricePerM3 = kwhPerM3 * r.tariff().normal();

        double solar = opt.stream().mapToDouble(SimHour::solarKwh).sum();
        double grid = opt.stream().mapToDouble(SimHour::gridKwh).sum();
        double solarShare = (solar + grid) > 0 ? 100.0 * solar / (solar + grid) : 0;

        double costOpt = gridCost(r, opt) + shortage(opt) * shortagePricePerM3;
        double costBase = gridCost(r, base) + shortage(base) * shortagePricePerM3;

        double peakAvoided = Math.max(0, peakKwh(base) - peakKwh(opt));

        return new Kpis(
                round(solarShare, 1),
                round(costOpt, 2),
                round(costBase, 2),
                round(costBase - costOpt, 2),
                round(peakAvoided, 1),
                simulator.coveredOutageHours(opt),
                simulator.coveredOutageHours(base));
    }

    /** Facture réseau : kWh réseau x tarif de l'heure. */
    private double gridCost(PlanRequest r, List<SimHour> sim) {
        return sim.stream()
                .mapToDouble(s -> s.gridKwh() * tariffs.priceOf(s.hourOfDay(), r.tariff()))
                .sum();
    }

    /** Eau demandée par la culture mais non livrée (m3). */
    private double shortage(List<SimHour> sim) {
        return sim.stream()
                .mapToDouble(s -> Math.max(0, s.needM3() - s.waterDeliveredM3()))
                .sum();
    }

    /** kWh réseau consommés pendant la pointe. */
    private double peakKwh(List<SimHour> sim) {
        return sim.stream()
                .filter(s -> tariffs.bandOf(s.hourOfDay()) == TariffService.Band.PEAK)
                .mapToDouble(SimHour::gridKwh)
                .sum();
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}