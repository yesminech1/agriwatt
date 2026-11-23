package tn.agriwatt.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tn.agriwatt.backend.dto.Kpis;
import tn.agriwatt.backend.dto.PlanRequest;
import tn.agriwatt.backend.service.BaselinePlanner;
import tn.agriwatt.backend.service.KpiService;
import tn.agriwatt.backend.service.PumpOptimizer;
import tn.agriwatt.backend.service.PythonClient;
import tn.agriwatt.backend.service.TankSimulator;

@RestController
@RequestMapping("/api/debug")
public class KpiDebugController {

    private final PythonClient python;
    private final TankSimulator simulator;
    private final BaselinePlanner baseline;
    private final PumpOptimizer optimizer;
    private final KpiService kpis;

    public KpiDebugController(PythonClient python, TankSimulator simulator,
                              BaselinePlanner baseline, PumpOptimizer optimizer, KpiService kpis) {
        this.python = python;
        this.simulator = simulator;
        this.baseline = baseline;
        this.optimizer = optimizer;
        this.kpis = kpis;
    }

    @GetMapping("/kpis")
    public Kpis kpis() {
        var r = PlanRequest.defaults();
        var hours = python.forecast(r).hours();
        var opt = simulator.simulate(r, hours, optimizer.plan(r, hours));
        var base = simulator.simulate(r, hours, baseline.plan(hours));
        return kpis.compute(r, opt, base);
    }
}