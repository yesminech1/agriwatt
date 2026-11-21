package tn.agriwatt.backend.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tn.agriwatt.backend.dto.Kpis;
import tn.agriwatt.backend.dto.PlanRequest;
import tn.agriwatt.backend.dto.PlanResponse;
import tn.agriwatt.backend.dto.ScheduleItem;

@RestController
@RequestMapping("/api")
public class PlanController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @GetMapping("/config/default")
    public PlanRequest defaultConfig() {
        return PlanRequest.defaults();
    }

    @PostMapping("/plan")
    public PlanResponse plan(@RequestBody PlanRequest req) {
        // DONNÉES FACTICES : seront remplacées par l'optimiseur (étape 7)
        List<ScheduleItem> optimized = fakeSchedule(req, Set.of(10, 11, 12), "solar");
        List<ScheduleItem> baseline = fakeSchedule(req, Set.of(6, 7), "grid");
        Kpis kpis = new Kpis(62.0, 8.4, 14.9, 6.5, 18.0, 5, 1);
        return new PlanResponse(12, optimized, baseline, kpis);
    }

    private List<ScheduleItem> fakeSchedule(PlanRequest r, Set<Integer> pumpHours, String source) {
        List<ScheduleItem> list = new ArrayList<>();
        LocalDateTime start = LocalDate.of(2026, 7, 15).atStartOfDay();
        double level = r.tankInitialM3();

        for (int h = 0; h < r.days() * 24; h++) {
            LocalDateTime t = start.plusHours(h);
            int hod = t.getHour();
            boolean outage = hod >= r.outageStartHour() && hod < r.outageEndHour();
            boolean on = pumpHours.contains(hod);
            double pumped = on ? r.pumpFlowM3h() : 0;
            double need = (hod >= 8 && hod <= 20) ? 0.9 : 0.1;
            double delivered = Math.min(need, level + pumped);
            level = Math.max(0, Math.min(r.tankCapacityM3(), level + pumped - delivered));

            list.add(new ScheduleItem(
                    t.format(FMT), on, on ? source : "none",
                    on ? r.pumpPowerKw() : 0,
                    round1(pumped), round1(delivered), round1(level), outage));
        }
        return list;
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}