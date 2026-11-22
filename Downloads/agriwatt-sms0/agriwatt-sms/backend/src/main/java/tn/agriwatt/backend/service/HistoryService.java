package tn.agriwatt.backend.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tn.agriwatt.backend.dto.Kpis;
import tn.agriwatt.backend.dto.PlanRequest;
import tn.agriwatt.backend.dto.RunDtos.RunDetail;
import tn.agriwatt.backend.dto.RunDtos.RunSummary;
import tn.agriwatt.backend.dto.ScheduleItem;
import tn.agriwatt.backend.entity.Farm;
import tn.agriwatt.backend.entity.PlanRun;
import tn.agriwatt.backend.entity.ScheduleHour;
import tn.agriwatt.backend.repository.PlanRunRepository;
import tn.agriwatt.backend.repository.ScheduleHourRepository;
import tools.jackson.databind.json.JsonMapper;

/** Historique des calculs : toujours filtré par l'utilisateur connecté (propriétaire de la ferme). */
@Service
@Transactional(readOnly = true)
public class HistoryService {

    private final PlanRunRepository runs;
    private final ScheduleHourRepository scheduleHours;
    private final FarmService farms;
    private final JsonMapper mapper;

    public HistoryService(PlanRunRepository runs, ScheduleHourRepository scheduleHours, FarmService farms,
                          JsonMapper mapper) {
        this.runs = runs;
        this.scheduleHours = scheduleHours;
        this.farms = farms;
        this.mapper = mapper;
    }

    /** Calculs d'une ferme de l'utilisateur, du plus récent au plus ancien, filtrables par période (dates incluses). */
    public List<RunSummary> forFarm(String ownerEmail, Long farmId, LocalDate from, LocalDate to) {
        Farm farm = farms.ownedEntity(ownerEmail, farmId);              // 404 si ce n'est pas sa ferme
        Long userId = farms.ownerId(ownerEmail);
        List<PlanRun> list;
        if (from == null && to == null) {
            list = runs.findByFarmIdAndFarmUserIdOrderByCreatedAtDesc(farm.getId(), userId);
        } else {
            LocalDateTime start = (from == null ? LocalDate.of(2000, 1, 1) : from).atStartOfDay();
            LocalDateTime end = (to == null ? LocalDate.of(2999, 12, 31) : to).atTime(LocalTime.MAX);
            if (end.isBefore(start)) throw new IllegalArgumentException("La date de fin doit être après la date de début.");
            list = runs.findByFarmIdAndFarmUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(farm.getId(), userId, start, end);
        }
        return list.stream().map(run -> summary(run, farm)).toList();
    }

    /** Tous les calculs de l'utilisateur (toutes ses fermes). */
    public List<RunSummary> forUser(String ownerEmail) {
        return runs.findByFarmUserIdOrderByCreatedAtDesc(farms.ownerId(ownerEmail)).stream()
                .map(run -> summary(run, run.getFarm())).toList();
    }

    /** Détail d'un ancien calcul relu en base, sans relancer d'optimisation. */
    public RunDetail detail(String ownerEmail, Long runId) {
        PlanRun run = runs.findByIdAndFarmUserId(runId, farms.ownerId(ownerEmail))
                .orElseThrow(() -> new FarmService.FarmNotFoundException("Calcul introuvable."));
        PlanRequest config = read(run.getConfigJson(), PlanRequest.class);
        Kpis kpis = read(run.getKpisJson(), Kpis.class);
        List<ScheduleHour> rows = scheduleHours.findByRunIdOrderByIdAsc(run.getId());
        List<ScheduleItem> schedule = rows.stream().filter(h -> !h.isBaseline()).map(HistoryService::item).toList();
        List<ScheduleItem> baseline = rows.stream().filter(ScheduleHour::isBaseline).map(HistoryService::item).toList();
        return new RunDetail(summary(run, run.getFarm()), config, kpis, schedule, baseline);
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return json == null ? null : mapper.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    private static ScheduleItem item(ScheduleHour h) {
        return new ScheduleItem(h.getHour(), h.isPumpOn(), h.getSource(), h.getPumpKw(), h.getWaterPumpedM3(),
                h.getWaterDeliveredM3(), h.getTankLevelM3(), h.isOutage(),
                h.getSolarKw() == null ? 0 : h.getSolarKw(), h.getTempC() == null ? 0 : h.getTempC());
    }

    private static RunSummary summary(PlanRun r, Farm farm) {
        return new RunSummary(r.getId(), farm == null ? null : farm.getId(), farm == null ? null : farm.getName(),
                String.valueOf(r.getCreatedAt()), r.getLat(), r.getLon(), r.getCrop(), r.getAreaM2(), r.getDays(),
                r.getForecastStart(), r.getForecastEnd(), r.getWeatherSource(),
                r.getSolarKwh(), r.getGridKwh(), r.getPumpEnergyKwh(), r.getWaterNeedM3(), r.getWaterDeliveredM3(),
                r.getSavingsTnd(), r.getCostOptimizedTnd(), r.getCostBaselineTnd(), r.getSolarSharePct(),
                r.getGridPeakKwhAvoided(), r.getOutageHoursCovered(), r.getBaselineOutageHoursCovered(),
                r.getMinTankLevelM3(), r.getFinalTankLevelM3());
    }
}
