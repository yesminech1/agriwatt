package tn.agriwatt.backend.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tn.agriwatt.backend.dto.ForecastResponse;
import tn.agriwatt.backend.dto.PlanRequest;
import tn.agriwatt.backend.service.BaselinePlanner;
import tn.agriwatt.backend.service.PumpOptimizer;
import tn.agriwatt.backend.service.PythonClient;
import tn.agriwatt.backend.service.SimHour;
import tn.agriwatt.backend.service.TankSimulator;
import tn.agriwatt.backend.service.TariffService;

@RestController
@RequestMapping("/api/debug")
public class DebugController {

    private final PythonClient python;
    private final TariffService tariffs;
    private final TankSimulator simulator;
    private final BaselinePlanner baseline;
    private final PumpOptimizer optimizer;

    public DebugController(PythonClient python, TariffService tariffs, TankSimulator simulator,
                           BaselinePlanner baseline, PumpOptimizer optimizer) {
        this.python = python;
        this.tariffs = tariffs;
        this.simulator = simulator;
        this.baseline = baseline;
        this.optimizer = optimizer;
    }

    @GetMapping("/forecast")
    public ForecastResponse forecast() {
        return python.forecast(PlanRequest.defaults());
    }

    @GetMapping("/tariff")
    public List<String> tariff() {
        var t = PlanRequest.defaults().tariff();
        return IntStream.range(0, 24)
                .mapToObj(h -> h + "h : " + tariffs.bandOf(h) + " = " + tariffs.priceOf(h, t) + " TND")
                .toList();
    }

    @GetMapping("/baseline")
    public List<String> baselineTest() {
        var r = PlanRequest.defaults();
        var hours = python.forecast(r).hours();
        return format(r, simulator.simulate(r, hours, baseline.plan(hours)));
    }

    @GetMapping("/optimized")
    public List<String> optimizedTest() {
        var r = PlanRequest.defaults();
        var hours = python.forecast(r).hours();
        long t0 = System.nanoTime();
        boolean[] plan = optimizer.plan(r, hours);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        List<String> out = format(r, simulator.simulate(r, hours, plan));
        out.add("Temps de calcul de l'optimiseur : " + ms + " ms");
        return out;
    }

    /** 24 premières heures + résumé sur toute la période. */
    private List<String> format(PlanRequest r, List<SimHour> sim) {
        List<String> out = new ArrayList<>();
        for (SimHour s : sim.subList(0, 24)) {
            out.add(String.format(Locale.ROOT,
                    "%02dh | pompe=%-5s %-5s %.1fkW | pompe=%.1f livre=%.1f/%.1f | reservoir=%.1f%s",
                    s.hourOfDay(), s.pumpOn(), s.source(), s.pumpKw(),
                    s.waterPumpedM3(), s.waterDeliveredM3(), s.needM3(), s.tankLevelM3(),
                    s.outage() ? "  [COUPURE]" : ""));
        }
        long outageHours = sim.stream().filter(SimHour::outage).count();
        double water = sim.stream().mapToDouble(SimHour::waterDeliveredM3).sum();
        double need = sim.stream().mapToDouble(SimHour::needM3).sum();
        double solar = sim.stream().mapToDouble(SimHour::solarKwh).sum();
        double grid = sim.stream().mapToDouble(SimHour::gridKwh).sum();
        double cost = sim.stream()
                .mapToDouble(s -> s.gridKwh() * tariffs.priceOf(s.hourOfDay(), r.tariff())).sum();
        out.add("Heures de coupure couvertes : " + simulator.coveredOutageHours(sim) + " / " + outageHours);
        out.add(String.format(Locale.ROOT,
                "48 h : eau livree %.1f / %.1f m3 | solaire %.1f kWh | reseau %.1f kWh | cout reseau %.2f TND",
                water, need, solar, grid, cost));
        return out;
    }
}