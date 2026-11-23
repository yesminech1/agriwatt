package tn.agriwatt.backend.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tn.agriwatt.backend.dto.ForecastHour;
import tn.agriwatt.backend.dto.ForecastResponse;
import tn.agriwatt.backend.dto.Kpis;
import tn.agriwatt.backend.dto.PlanRequest;
import tn.agriwatt.backend.dto.PlanResponse;
import tn.agriwatt.backend.dto.ScheduleItem;
import tn.agriwatt.backend.entity.Farm;
import tn.agriwatt.backend.entity.PlanRun;
import tn.agriwatt.backend.entity.ScheduleHour;
import tn.agriwatt.backend.repository.PlanRunRepository;
import tn.agriwatt.backend.repository.ScheduleHourRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calcul d'optimisation pour une ferme de l'utilisateur connecté :
 * paramètres relus en base -> météo réelle (Python) -> optimisation -> enregistrement dans l'historique.
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private final PythonClient python;
    private final TankSimulator simulator;
    private final BaselinePlanner baselinePlanner;
    private final PumpOptimizer optimizer;
    private final KpiService kpiService;
    private final PlanRunRepository runs;
    private final ScheduleHourRepository scheduleRepo;
    private final JsonMapper mapper;
    private final FarmService farms;
    private final OutageAlertService outageAlerts;

    public PlanService(PythonClient python, TankSimulator simulator, BaselinePlanner baselinePlanner,
                       PumpOptimizer optimizer, KpiService kpiService,
                       PlanRunRepository runs, ScheduleHourRepository scheduleRepo, JsonMapper mapper,
                       FarmService farms, OutageAlertService outageAlerts) {
        this.python = python;
        this.simulator = simulator;
        this.baselinePlanner = baselinePlanner;
        this.optimizer = optimizer;
        this.kpiService = kpiService;
        this.runs = runs;
        this.scheduleRepo = scheduleRepo;
        this.mapper = mapper;
        this.farms = farms;
        this.outageAlerts = outageAlerts;
    }

    public PlanResponse plan(PlanRequest req, String ownerEmail) {
        if (req == null || req.farmId() == null) {
            throw new IllegalArgumentException("Sélectionnez une ferme dans Paramètres avant de lancer un calcul.");
        }
        if (ownerEmail == null) throw new FarmService.FarmNotFoundException("Ferme introuvable.");
        Farm farm = farms.ownedEntity(ownerEmail, req.farmId());       // 404 si la ferme n'est pas à lui
        PlanRequest r = fromFarm(farm);                                  // paramètres ENREGISTRÉS, jamais ceux du navigateur

        ForecastResponse forecast = python.forecast(r);                 // météo réelle, sinon 503
        List<ForecastHour> hours = forecast.hours();

        List<SimHour> opt = simulator.simulate(r, hours, optimizer.plan(r, hours));
        List<SimHour> base = simulator.simulate(r, hours, baselinePlanner.plan(hours));
        Kpis kpis = kpiService.compute(r, opt, base);

        List<ScheduleItem> schedule = opt.stream().map(SimHour::toItem).toList();
        List<ScheduleItem> baselineSchedule = base.stream().map(SimHour::toItem).toList();

        PlanRun run = save(farm, r, forecast, opt, kpis, schedule, baselineSchedule);
        try {                                    // la même prévision sert à programmer les alertes SMS de coupure
            outageAlerts.scheduleFromForecast(farm, hours, OutageAlertService.now());
        } catch (Exception e) {
            log.warn("Programmation des alertes impossible pour la ferme {} : {}", farm.getId(), e.getMessage());
        }
        return new PlanResponse(run.getId(), schedule, baselineSchedule, kpis,
                forecast.source(), r.lat(), r.lon());
    }

    static PlanRequest fromFarm(Farm f) {
        return new PlanRequest(f.getLat(), f.getLon(), f.getCrop(), f.getAreaM2(), f.getPanelAreaM2(),
                f.getPanelEfficiency(), f.getPumpPowerKw(), f.getPumpFlowM3h(), f.getTankCapacityM3(),
                f.getTankInitialM3(), f.getOutageStartHour(), f.getOutageEndHour(), f.getDays(),
                new PlanRequest.Tariff(f.getOffPeakTariff(), f.getNormalTariff(), f.getPeakTariff()), f.getId());
    }

    /** Enregistrement systématique dans l'historique de la ferme (une erreur de base n'est pas masquée). */
    private PlanRun save(Farm farm, PlanRequest r, ForecastResponse forecast, List<SimHour> opt, Kpis kpis,
                         List<ScheduleItem> schedule, List<ScheduleItem> baseline) {
        PlanRun run = new PlanRun();
        run.setFarm(farm);
        run.setConfigJson(mapper.writeValueAsString(r));
        run.setKpisJson(mapper.writeValueAsString(kpis));
        run.setLat(r.lat());
        run.setLon(r.lon());
        run.setCrop(r.crop());
        run.setAreaM2(r.areaM2());
        run.setDays(r.days());
        run.setWeatherSource(forecast.source());
        if (!opt.isEmpty()) {
            run.setForecastStart(opt.get(0).hour());
            run.setForecastEnd(opt.get(opt.size() - 1).hour());
            run.setFinalTankLevelM3(r2(opt.get(opt.size() - 1).tankLevelM3()));
        }
        double solar = opt.stream().mapToDouble(SimHour::solarKwh).sum();
        double grid = opt.stream().mapToDouble(SimHour::gridKwh).sum();
        run.setSolarKwh(r2(solar));
        run.setGridKwh(r2(grid));
        run.setPumpEnergyKwh(r2(solar + grid));
        run.setWaterNeedM3(r2(opt.stream().mapToDouble(SimHour::needM3).sum()));
        run.setWaterDeliveredM3(r2(opt.stream().mapToDouble(SimHour::waterDeliveredM3).sum()));
        run.setMinTankLevelM3(r2(opt.stream().mapToDouble(SimHour::tankLevelM3).min().orElse(0)));
        run.setSavingsTnd(kpis.savingsTnd());
        run.setCostOptimizedTnd(kpis.costOptimizedTnd());
        run.setCostBaselineTnd(kpis.costBaselineTnd());
        run.setSolarSharePct(kpis.solarSharePct());
        run.setGridPeakKwhAvoided(kpis.gridPeakKwhAvoided());
        run.setOutageHoursCovered(kpis.outageHoursCovered());
        run.setBaselineOutageHoursCovered(kpis.baselineOutageHoursCovered());
        run = runs.save(run);

        List<ScheduleHour> rows = new ArrayList<>();
        for (ScheduleItem s : schedule) rows.add(toRow(run.getId(), s, false));
        for (ScheduleItem s : baseline) rows.add(toRow(run.getId(), s, true));
        scheduleRepo.saveAll(rows);
        return run;
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
        h.setSolarKw(s.solarKw());
        h.setTempC(s.tempC());
        return h;
    }

    private static double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
