package tn.agriwatt.backend.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tn.agriwatt.backend.dto.FarmDtos.FarmRequest;
import tn.agriwatt.backend.dto.FarmDtos.FarmResponse;
import tn.agriwatt.backend.entity.AppUser;
import tn.agriwatt.backend.entity.Farm;
import tn.agriwatt.backend.entity.PlanRun;
import tn.agriwatt.backend.repository.AnomalyEventRepository;
import tn.agriwatt.backend.repository.AppUserRepository;
import tn.agriwatt.backend.repository.FarmRepository;
import tn.agriwatt.backend.repository.PlanRunRepository;
import tn.agriwatt.backend.repository.ScheduleHourRepository;
import tn.agriwatt.backend.repository.SmsAlertRepository;

/**
 * CRUD des fermes de l'utilisateur connecté.
 * Le propriétaire est TOUJOURS déterminé à partir de l'identité de session (e-mail), jamais du JSON.
 * Chaque lecture / modification / suppression cherche la ferme par (id, propriétaire).
 */
@Service
@Transactional
public class FarmService {

    private final FarmRepository farms;
    private final AppUserRepository users;
    private final PlanRunRepository runs;
    private final ScheduleHourRepository scheduleHours;
    private final AnomalyEventRepository anomalies;
    private final SmsAlertRepository smsAlerts;

    public FarmService(FarmRepository farms, AppUserRepository users, PlanRunRepository runs,
                       ScheduleHourRepository scheduleHours, AnomalyEventRepository anomalies,
                       SmsAlertRepository smsAlerts) {
        this.farms = farms;
        this.users = users;
        this.runs = runs;
        this.scheduleHours = scheduleHours;
        this.anomalies = anomalies;
        this.smsAlerts = smsAlerts;
    }

    @Transactional(readOnly = true)
    public List<FarmResponse> list(String ownerEmail) {
        return farms.findAllByUserIdOrderByCreatedAtAsc(owner(ownerEmail).getId())
                .stream().map(FarmService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public FarmResponse get(String ownerEmail, Long farmId) {
        return toResponse(owned(ownerEmail, farmId));
    }

    public FarmResponse create(String ownerEmail, FarmRequest req) {
        checkConsistency(req);
        Farm farm = new Farm(owner(ownerEmail));
        apply(farm, req);
        return toResponse(farms.saveAndFlush(farm));
    }

    public FarmResponse update(String ownerEmail, Long farmId, FarmRequest req) {
        Farm farm = owned(ownerEmail, farmId);
        checkConsistency(req);
        apply(farm, req);
        return toResponse(farms.saveAndFlush(farm));
    }

    /** Supprime la ferme ET ses données (historique des calculs, mesures de pompe importées). */
    public void delete(String ownerEmail, Long farmId) {
        Farm farm = owned(ownerEmail, farmId);
        List<PlanRun> farmRuns = runs.findByFarmId(farm.getId());
        if (!farmRuns.isEmpty()) {
            scheduleHours.deleteByRunIdIn(farmRuns.stream().map(PlanRun::getId).toList());
            runs.deleteAll(farmRuns);
        }
        anomalies.deleteByFarmId(farm.getId());
        smsAlerts.deleteByFarmId(farm.getId());
        farms.delete(farm);
    }

    /** Ferme de l'utilisateur connecté (pour les calculs, l'historique et les anomalies) ; 404 sinon. */
    @Transactional(readOnly = true)
    public Farm ownedEntity(String ownerEmail, Long farmId) {
        return owned(ownerEmail, farmId);
    }

    /** Identifiant interne de l'utilisateur connecté (filtre de l'historique). */
    @Transactional(readOnly = true)
    public Long ownerId(String ownerEmail) {
        return owner(ownerEmail).getId();
    }

    // ---------- sécurité ----------

    private AppUser owner(String email) {
        return users.findByEmail(email).orElseThrow(() -> new FarmNotFoundException("Utilisateur inconnu."));
    }

    private Farm owned(String ownerEmail, Long farmId) {
        return farms.findByIdAndUserId(farmId, owner(ownerEmail).getId())
                .orElseThrow(() -> new FarmNotFoundException("Ferme introuvable."));
    }

    // ---------- règles qui portent sur plusieurs champs ----------

    private static void checkConsistency(FarmRequest r) {
        if (!TunisiaGeo.contains(r.lat(), r.lon())) {
            throw new FarmValidationException(Map.of("lat",
                    "La position sélectionnée n'est pas située en Tunisie (zone terrestre approximative)."));
        }
        if (r.tankInitialM3() > r.tankCapacityM3()) {
            throw new FarmValidationException(Map.of("tankInitialM3",
                    "Le niveau initial doit être compris entre 0 et la capacité du réservoir."));
        }
        boolean hasStart = r.outageStartHour() != null, hasEnd = r.outageEndHour() != null;
        if (hasStart != hasEnd) {
            throw new FarmValidationException(Map.of(hasStart ? "outageEndHour" : "outageStartHour",
                    "Indiquez l'heure de début ET l'heure de fin de la coupure (ou aucune des deux)."));
        }
        if (hasStart && r.outageStartHour() >= r.outageEndHour()) {
            throw new FarmValidationException(Map.of("outageEndHour",
                    "L'heure de fin de coupure doit être après l'heure de début."));
        }
    }

    private static void apply(Farm f, FarmRequest r) {
        f.setName(r.name().trim());
        f.setLat(r.lat());
        f.setLon(r.lon());
        f.setCrop(r.crop());
        f.setAreaM2(r.areaM2());
        f.setPanelAreaM2(r.panelAreaM2());
        f.setPanelEfficiency(r.panelEfficiency());
        f.setPumpPowerKw(r.pumpPowerKw());
        f.setPumpFlowM3h(r.pumpFlowM3h());
        f.setTankCapacityM3(r.tankCapacityM3());
        f.setTankInitialM3(r.tankInitialM3());
        f.setOutageStartHour(r.outageStartHour());
        f.setOutageEndHour(r.outageEndHour());
        f.setDays(r.days());
        f.setOffPeakTariff(r.offPeakTariff());
        f.setNormalTariff(r.normalTariff());
        f.setPeakTariff(r.peakTariff());
    }

    private static FarmResponse toResponse(Farm f) {
        return new FarmResponse(f.getId(), f.getName(), f.getLat(), f.getLon(), f.getCrop(),
                f.getAreaM2(), f.getPanelAreaM2(), f.getPanelEfficiency(),
                f.getPumpPowerKw(), f.getPumpFlowM3h(), f.getTankCapacityM3(), f.getTankInitialM3(),
                f.getOutageStartHour(), f.getOutageEndHour(), f.getDays(),
                f.getOffPeakTariff(), f.getNormalTariff(), f.getPeakTariff(),
                f.getCreatedAt(), f.getUpdatedAt());
    }

    /** Ferme absente OU appartenant à un autre utilisateur : même réponse 404 (on ne révèle pas son existence). */
    public static class FarmNotFoundException extends RuntimeException {
        public FarmNotFoundException(String message) { super(message); }
    }

    /** Erreur de cohérence entre champs, renvoyée en 400 avec le champ concerné. */
    public static class FarmValidationException extends RuntimeException {
        private final Map<String, String> errors;
        public FarmValidationException(Map<String, String> errors) {
            super("Paramètres de la ferme invalides.");
            this.errors = errors;
        }
        public Map<String, String> getErrors() { return errors; }
    }
}
