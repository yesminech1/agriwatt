package tn.agriwatt.backend.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tn.agriwatt.backend.dto.AnomalyDtos.FarmAnomalies;
import tn.agriwatt.backend.dto.AnomalyDtos.ImportRequest;
import tn.agriwatt.backend.dto.AnomalyDtos.PumpPoint;
import tn.agriwatt.backend.dto.AnomalyPoint;
import tn.agriwatt.backend.dto.AnomalyResponse;
import tn.agriwatt.backend.entity.AnomalyEvent;
import tn.agriwatt.backend.entity.Farm;
import tn.agriwatt.backend.repository.AnomalyEventRepository;

/**
 * Anomalies de pompe à partir de mesures RÉELLES importées (CSV) pour une ferme de l'utilisateur connecté.
 * Un nouvel import remplace le précédent jeu de mesures de la ferme.
 */
@Service
@Transactional
public class AnomalyService {

    public static final int MAX_POINTS = 50_000;
    private static final Pattern TS = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?$");

    private final AnomalyEventRepository events;
    private final FarmService farms;
    private final PythonClient python;

    public AnomalyService(AnomalyEventRepository events, FarmService farms, PythonClient python) {
        this.events = events;
        this.farms = farms;
        this.python = python;
    }

    @Transactional(readOnly = true)
    public FarmAnomalies get(String ownerEmail, Long farmId) {
        Farm farm = farms.ownedEntity(ownerEmail, farmId);
        return toResponse(farm.getId(), events.findByFarmIdOrderByTimestampAsc(farm.getId()));
    }

    public FarmAnomalies importMeasurements(String ownerEmail, Long farmId, ImportRequest req) {
        Farm farm = farms.ownedEntity(ownerEmail, farmId);
        List<PumpPoint> points = validate(req);
        AnomalyResponse analysed = python.detect(points);                // 503 si l'analyse est indisponible

        events.deleteByFarmId(farm.getId());                            // remplace l'import précédent
        LocalDateTime now = LocalDateTime.now();
        String fileName = req.fileName() == null ? null : req.fileName().strip();
        if (fileName != null && fileName.length() > 255) fileName = fileName.substring(0, 255);
        List<AnomalyEvent> rows = new ArrayList<>();
        for (AnomalyPoint p : analysed.points()) {
            AnomalyEvent e = new AnomalyEvent();
            e.setFarm(farm);
            e.setTimestamp(p.timestamp());
            e.setPowerKw(p.powerKw());
            e.setAnomaly(p.isAnomaly());
            e.setScore(p.score());
            e.setReason(p.reason());
            e.setImportedAt(now);
            e.setFileName(fileName);
            rows.add(e);
        }
        return toResponse(farm.getId(), events.saveAll(rows));
    }

    public void clear(String ownerEmail, Long farmId) {
        events.deleteByFarmId(farms.ownedEntity(ownerEmail, farmId).getId());
    }

    private static List<PumpPoint> validate(ImportRequest req) {
        if (req == null || req.points() == null || req.points().isEmpty()) {
            throw new IllegalArgumentException("Le fichier ne contient aucune mesure.");
        }
        if (req.points().size() < 20) {
            throw new IllegalArgumentException("Au moins 20 mesures sont nécessaires pour analyser le fonctionnement de la pompe.");
        }
        if (req.points().size() > MAX_POINTS) {
            throw new IllegalArgumentException("Trop de mesures (maximum " + MAX_POINTS + ").");
        }
        for (int i = 0; i < req.points().size(); i++) {
            PumpPoint p = req.points().get(i);
            if (p == null || p.timestamp() == null || !TS.matcher(p.timestamp().strip()).matches()) {
                throw new IllegalArgumentException("Ligne " + (i + 1) + " : date invalide (format attendu 2026-07-15T13:00).");
            }
            if (p.powerKw() == null || p.powerKw().isNaN() || p.powerKw() < 0 || p.powerKw() > 1000) {
                throw new IllegalArgumentException("Ligne " + (i + 1) + " : puissance invalide (kW positif attendu).");
            }
        }
        return req.points().stream()
                .map(p -> new PumpPoint(p.timestamp().strip().substring(0, 16), p.powerKw()))
                .sorted(Comparator.comparing(PumpPoint::timestamp))
                .toList();
    }

    private static FarmAnomalies toResponse(Long farmId, List<AnomalyEvent> rows) {
        List<AnomalyPoint> points = rows.stream()
                .sorted(Comparator.comparing(AnomalyEvent::getTimestamp))
                .map(e -> new AnomalyPoint(e.getTimestamp(), e.getPowerKw(), e.isAnomaly(), e.getScore(), e.getReason()))
                .toList();
        AnomalyEvent first = rows.isEmpty() ? null : rows.get(0);
        return new FarmAnomalies(farmId, first == null ? null : first.getFileName(),
                first == null || first.getImportedAt() == null ? null : first.getImportedAt().toString(),
                points.size(), (int) points.stream().filter(AnomalyPoint::isAnomaly).count(), points);
    }
}
