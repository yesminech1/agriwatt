package tn.agriwatt.backend.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tn.agriwatt.backend.dto.PlanRequest;
import tn.agriwatt.backend.dto.PlanResponse;
import tn.agriwatt.backend.entity.PlanRun;
import tn.agriwatt.backend.entity.ScheduleHour;
import tn.agriwatt.backend.repository.PlanRunRepository;
import tn.agriwatt.backend.repository.ScheduleHourRepository;
import tn.agriwatt.backend.service.PlanService;

@RestController
@RequestMapping("/api")
public class PlanController {

    private final PlanService planService;
    private final PlanRunRepository runs;
    private final ScheduleHourRepository schedules;

    public PlanController(PlanService planService, PlanRunRepository runs, ScheduleHourRepository schedules) {
        this.planService = planService;
        this.runs = runs;
        this.schedules = schedules;
    }

    @GetMapping("/config/default")
    public PlanRequest defaultConfig() {
        return PlanRequest.defaults();
    }

    @PostMapping("/plan")
    public PlanResponse plan(@RequestBody PlanRequest req) {
        return planService.plan(req);
    }

    @GetMapping("/runs")
    public List<PlanRun> history() {
        return runs.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<Map<String, Object>> run(@PathVariable Long id) {
        return runs.findById(id)
                .map(run -> {
                    List<ScheduleHour> rows = schedules.findByRunIdOrderByIdAsc(id);
                    return ResponseEntity.ok(Map.<String, Object>of("run", run, "schedule", rows));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}