package tn.agriwatt.backend.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import tn.agriwatt.backend.dto.ForecastHour;
import tn.agriwatt.backend.dto.Kpis;
import tn.agriwatt.backend.dto.PlanRequest;
import tn.agriwatt.backend.dto.PlanResponse;
import tn.agriwatt.backend.dto.ScheduleItem;
import tn.agriwatt.backend.entity.PlanRun;
import tn.agriwatt.backend.entity.ScheduleHour;
import tn.agriwatt.backend.repository.PlanRunRepository;
import tn.agriwatt.backend.repository.ScheduleHourRepository;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PlanService {

    private final PythonClient python;
    private final TankSimulator simulator;
    private final BaselinePlanner baselinePlanner;
    private final PumpOptimizer optimizer;
    private final KpiService kpiService;
    private final PlanRunRepository runs;
    private final ScheduleHourRepository scheduleRepo;
    private final JsonMapper mapper;

    public PlanService(PythonClient python, TankSimulator simulator, BaselinePlanner baselinePlanner,
                       PumpOptimizer optimizer, KpiService kpiService,
                       PlanRunRepository runs, ScheduleHourRepository scheduleRepo, JsonMapper mapper) {
        this.python = python;
        this.simulator = simulator;
        this.baselinePlanner = baselinePlanner;
        this.optimizer = optimizer;
        this.kpiService = kpiService;
        this.runs = runs;
        this.scheduleRepo = scheduleRepo;
        this.mapper = mapper;
    }

    public PlanResponse plan(PlanRequest r) {
        List<ForecastHour> hours = python.forecast(r).hours();

        List<SimHour> opt = simulator.simulate(r, hours, optimizer.plan(r, hours));
        List<SimHour> base = simulator.simulate(r, hours, baselinePlanner.plan(hours));
        Kpis kpis = kpiService.compute(r, opt, base);

        List<ScheduleItem> schedule = opt.stream().map(SimHour::toItem).toList();
        List<ScheduleItem> baselineSchedule = base.stream().map(SimHour::toItem).toList();

        long runId = save(r, kpis, schedule, baselineSchedule);
        return new PlanResponse(runId, schedule, baselineSchedule, kpis);
    }

    /** Sauvegarde en MySQL. Si la base tombe, le plan est quand même renvoyé (runId = -1). */
    private long save(PlanRequest r, Kpis kpis, List<ScheduleItem> opt, List<ScheduleItem> base) {
        try {
            PlanRun run = new PlanRun();
            run.setConfigJson(mapper.writeValueAsString(r));
            run.setKpisJson(mapper.writeValueAsString(kpis));
            run = runs.save(run);

            List<ScheduleHour> rows = new ArrayList<>();
            for (ScheduleItem s : opt) rows.add(toRow(run.getId(), s, false));
            for (ScheduleItem s : base) rows.add(toRow(run.getId(), s, true));
            scheduleRepo.saveAll(rows);
            return run.getId();
        } catch (Exception e) {
            System.err.println("Sauvegarde MySQL impossible : " + e.getMessage());
            return -1;
        }
    }

    private ScheduleHour toRow(Long runId, ScheduleItem s, boolean baseline) {
        ScheduleHour h = new ScheduleHour();
        h.setRunId(runId);
        h.setHour(s.hour());
        h.setPumpOn(s.pumpOn());
        h.setSource(s.source());
        h.setPumpKw(s.pumpKw());
        h.setWaterPumpedM3(s.waterPumpedM3());
        h.setWaterDeliveredM3(s.waterDeliveredM3());
        h.setTankLevelM3(s.tankLevelM3());
        h.setOutage(s.outage());
        h.setBaseline(baseline);
        return h;
    }
}