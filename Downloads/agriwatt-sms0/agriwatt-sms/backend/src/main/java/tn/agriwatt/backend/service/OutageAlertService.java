package tn.agriwatt.backend.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tn.agriwatt.backend.dto.ForecastHour;
import tn.agriwatt.backend.entity.AppUser;
import tn.agriwatt.backend.entity.Farm;
import tn.agriwatt.backend.entity.SmsAlert;
import tn.agriwatt.backend.repository.AppUserRepository;
import tn.agriwatt.backend.repository.FarmRepository;
import tn.agriwatt.backend.repository.SmsAlertRepository;

/**
 * Alertes SMS de coupure :
 * 1. prédiction : heures où le risque de coupure (service Python ; 1.0 dans les horaires prévus de la ferme, sinon proxy
 *    chaleur + heures de pointe) dépasse le seuil -> fenêtres de coupure ;
 * 2. programmation : une alerte PENDING par coupure, envoi prévu 2 h avant le début (unicité ferme + début) ;
 * 3. envoi via TextBee quand l'heure est venue (PENDING -> SENT / FAILED / CANCELLED), nouvel essai si erreur temporaire ;
 * 4. suivi de livraison par smsBatchId (une réponse 200 n'est pas une preuve de réception).
 * Toutes les heures sont en heure de Tunisie (comme les prévisions Open-Meteo demandées en timezone Africa/Tunis).
 */
@Service
public class OutageAlertService {

    private static final Logger log = LoggerFactory.getLogger(OutageAlertService.class);
    public static final ZoneId TUNIS = ZoneId.of("Africa/Tunis");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH'h'mm");

    private final SmsAlertRepository alerts;
    private final AppUserRepository users;
    private final FarmRepository farms;
    private final TextBeeSmsService textBee;
    private final PythonClient python;
    private final TransactionTemplate tx;
    private final int leadHours;
    private final double riskThreshold;
    private final int maxAttempts;
    private final int retryMinutes;

    public OutageAlertService(SmsAlertRepository alerts, AppUserRepository users, FarmRepository farms,
                              TextBeeSmsService textBee, PythonClient python, PlatformTransactionManager txManager,
                              @Value("${agriwatt.alerts.lead-hours:2}") int leadHours,
                              @Value("${agriwatt.alerts.risk-threshold:0.8}") double riskThreshold,
                              @Value("${agriwatt.alerts.max-attempts:5}") int maxAttempts,
                              @Value("${agriwatt.alerts.retry-minutes:10}") int retryMinutes) {
        this.alerts = alerts;
        this.users = users;
        this.farms = farms;
        this.textBee = textBee;
        this.python = python;
        this.tx = new TransactionTemplate(txManager);
        this.leadHours = leadHours;
        this.riskThreshold = riskThreshold;
        this.maxAttempts = maxAttempts;
        this.retryMinutes = retryMinutes;
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(TUNIS).withNano(0);
    }

    public int getLeadHours() { return leadHours; }

    /** Fenêtre de coupure prédite. */
    public record OutageWindow(LocalDateTime start, LocalDateTime end, double riskMax, boolean planned) {}

    // ======================= 1. Prédiction =======================

    /** Regroupe les heures consécutives à risque élevé en fenêtres de coupure. */
    public List<OutageWindow> predict(Farm farm, List<ForecastHour> hours) {
        List<OutageWindow> out = new ArrayList<>();
        LocalDateTime start = null, last = null;
        double max = 0;
        boolean planned = false;
        for (ForecastHour h : hours) {
            LocalDateTime t = LocalDateTime.parse(h.hour());
            boolean risky = h.outageRisk() >= riskThreshold;
            if (risky && start != null && last != null && t.equals(last.plusHours(1))) {
                last = t; max = Math.max(max, h.outageRisk());
            } else {
                if (start != null) out.add(new OutageWindow(start, last.plusHours(1), max, planned));
                start = null;
                if (risky) { start = t; last = t; max = h.outageRisk(); planned = isPlanned(farm, t.getHour()); }
            }
        }
        if (start != null) out.add(new OutageWindow(start, last.plusHours(1), max, planned));
        return out;
    }

    private static boolean isPlanned(Farm f, int hour) {
        Integer s = f.getOutageStartHour(), e = f.getOutageEndHour();
        return s != null && e != null && s < e && hour >= s && hour < e;
    }

    // ======================= 2. Programmation =======================

    /**
     * À partir d'une prévision de la ferme : crée / met à jour les alertes futures (sans doublon) et annule celles
     * qui ne sont plus prédites. Ne fait rien si l'utilisateur n'a pas activé les alertes ou n'a pas de numéro.
     * @return nombre d'alertes en attente pour cette ferme après mise à jour
     */
    public int scheduleFromForecast(Farm detachedFarm, List<ForecastHour> hours, LocalDateTime now) {
        if (hours == null || hours.isEmpty()) return 0;
        Integer result = tx.execute(status -> {
            Farm farm = farms.findById(detachedFarm.getId()).orElse(null);      // relue dans la transaction (propriétaire chargé)
            if (farm == null) return 0;
            AppUser user = farm.getUser();
            Long userId = user.getId();
            LocalDateTime horizonStart = LocalDateTime.parse(hours.get(0).hour());
            LocalDateTime horizonEnd = LocalDateTime.parse(hours.get(hours.size() - 1).hour()).plusHours(1);
            List<SmsAlert> pendingInHorizon = alerts.findByFarmIdAndStatusAndOutageStartBetween(
                    farm.getId(), SmsAlert.Status.PENDING, horizonStart, horizonEnd);
            if (!user.isSmsAlertsEnabled() || user.getPhoneNumber() == null) {
                pendingInHorizon.forEach(a -> cancel(a, "Alertes SMS désactivées ou numéro absent."));
                return 0;
            }
            boolean severalFarms = farms.findAllByUserIdOrderByCreatedAtAsc(userId).size() > 1;
            Set<LocalDateTime> predicted = new HashSet<>();
            int pending = 0;
            for (OutageWindow w : predict(farm, hours)) {
                if (!w.start().isAfter(now)) continue;                     // coupure déjà commencée : trop tard
                predicted.add(w.start());
                LocalDateTime sendAt = w.start().minusHours(leadHours);
                if (sendAt.isBefore(now)) sendAt = now;                      // coupure dans moins de 2 h : envoi immédiat
                SmsAlert a = alerts.findByFarmIdAndOutageStart(farm.getId(), w.start()).orElse(null);
                if (a == null) {
                    a = new SmsAlert(user, farm, SmsAlert.Kind.OUTAGE);
                    a.setOutageStart(w.start());
                } else if (a.getStatus() == SmsAlert.Status.SENT || a.getStatus() == SmsAlert.Status.FAILED) {
                    continue;                                                // déjà traitée : pas de second SMS
                } else if (a.getStatus() == SmsAlert.Status.CANCELLED) {
                    a.setStatus(SmsAlert.Status.PENDING);                    // de nouveau prédite
                    a.setAttempts(0);
                    a.setLastError(null);
                }
                a.setOutageEnd(w.end());
                a.setRiskMax(w.riskMax());
                a.setPlannedOutage(w.planned());
                a.setScheduledAt(sendAt);
                a.setMessage(message(farm, w, severalFarms, sendAt));
                a.touch();
                alerts.save(a);
                pending++;
            }
            for (SmsAlert a : pendingInHorizon) {
                if (a.getOutageStart().isAfter(now) && !predicted.contains(a.getOutageStart())) {
                    cancel(a, "Coupure plus prévue par la dernière prévision.");
                }
            }
            return pending;
        });
        return result == null ? 0 : result;
    }

    /** Texte du SMS (une coupure saisie pour la ferme est « prévue » ; un risque estimé n'est pas présenté comme officiel). */
    String message(Farm farm, OutageWindow w, boolean severalFarms, LocalDateTime sendAt) {
        String when = (w.start().toLocalDate().isAfter(sendAt.toLocalDate()) ? "demain vers " : "vers ") + w.start().format(HOUR);
        String prefix = "Alerte AgriWatt" + (severalFarms ? " (" + farm.getName() + ")" : "") + " : ";
        String what = w.planned()
                ? "une coupure d'électricité est prévue " + when + "."
                : "un risque élevé de coupure d'électricité est estimé " + when + ".";
        return prefix + what + " Anticipez votre irrigation et remplissez votre réservoir.";
    }

    private static void cancel(SmsAlert a, String reason) {
        a.setStatus(SmsAlert.Status.CANCELLED);
        a.setLastError(reason);
    }

    /** Recalcule les prévisions de toutes les fermes dont le propriétaire a activé les alertes (tâche planifiée). */
    public void refreshAllPredictions(LocalDateTime now) {
        for (AppUser u : users.findBySmsAlertsEnabledTrue()) {
            if (u.getPhoneNumber() == null) continue;
            for (Farm f : farms.findAllByUserIdOrderByCreatedAtAsc(u.getId())) {
                try {
                    scheduleFromForecast(f, python.forecast(PlanService.fromFarm(f)).hours(), now);
                } catch (Exception e) {
                    log.warn("Prévision de coupure impossible pour la ferme {} : {}", f.getId(), e.getMessage());
                }
            }
        }
    }

    /** Annule toutes les alertes en attente d'un utilisateur (désactivation des alertes, numéro supprimé). */
    public void cancelPendingForUser(Long userId, String reason) {
        tx.executeWithoutResult(s -> alerts.findByUserIdAndStatus(userId, SmsAlert.Status.PENDING)
                .forEach(a -> { cancel(a, reason); alerts.save(a); }));
    }

    // ======================= 3. Envoi =======================

    /** Envoie les alertes dont l'heure est venue. Chaque alerte est traitée dans sa propre transaction. */
    public int dispatchDue(LocalDateTime now) {
        int processed = 0;
        for (SmsAlert due : alerts.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(SmsAlert.Status.PENDING, now)) {
            if (due.getNextAttemptAt() != null && due.getNextAttemptAt().isAfter(now)) continue;
            try {
                tx.executeWithoutResult(s -> alerts.findById(due.getId()).ifPresent(a -> process(a, now)));
                processed++;
            } catch (DataIntegrityViolationException | org.springframework.dao.OptimisticLockingFailureException e) {
                log.warn("Alerte {} modifiée en parallèle, ignorée ce tour-ci", due.getId());
            }
        }
        return processed;
    }

    private void process(SmsAlert a, LocalDateTime now) {
        if (a.getStatus() != SmsAlert.Status.PENDING) return;
        AppUser user = users.findById(a.getUser().getId()).orElse(null);
        if (a.getKind() == SmsAlert.Kind.OUTAGE) {
            if (user == null || !user.isSmsAlertsEnabled()) { cancel(a, "Alertes SMS désactivées par l'utilisateur."); alerts.save(a); return; }
            if (a.getOutageStart() != null && !a.getOutageStart().isAfter(now)) {
                if (a.getAttempts() > 0) { a.setStatus(SmsAlert.Status.FAILED); a.setLastError("Coupure commencée avant que l'envoi réussisse. " + nz(a.getLastError())); }
                else cancel(a, "Coupure déjà commencée : alerte trop tardive.");
                alerts.save(a);
                return;
            }
        }
        if (user == null || user.getPhoneNumber() == null) { cancel(a, "Aucun numéro de téléphone enregistré."); alerts.save(a); return; }
        attemptSend(a, user.getPhoneNumber(), now);
        alerts.save(a);
    }

    private void attemptSend(SmsAlert a, String phone, LocalDateTime now) {
        a.setRecipient(phone);
        try {
            TextBeeSmsService.SendResult r = textBee.send(phone, a.getMessage());
            a.setStatus(SmsAlert.Status.SENT);
            a.setSmsBatchId(r.smsBatchId());
            a.setSentAt(now);
            a.setProviderStatus("pending");
            a.setNextAttemptAt(null);
            a.setLastError("SMS_PERMISSION_MISSING".equals(r.warningCode())
                    ? "Avertissement TextBee : la permission SMS est désactivée sur le téléphone passerelle ; l'envoi échouera si elle n'est pas réactivée."
                    : null);
            log.info("Alerte {} transmise à TextBee (lot {}) pour {}", a.getId(), r.smsBatchId(), PhoneNumbers.mask(phone));
        } catch (TextBeeException e) {
            a.setLastError(e.getMessage());
            if (e.isConfigurationProblem()) {
                a.setNextAttemptAt(now.plusMinutes(retryMinutes));          // on réessaiera une fois la configuration corrigée
            } else if (e.getKind().retryable) {
                a.setAttempts(a.getAttempts() + 1);
                if (a.getAttempts() >= maxAttempts) a.setStatus(SmsAlert.Status.FAILED);
                else a.setNextAttemptAt(now.plusMinutes((long) retryMinutes * a.getAttempts()));
            } else {
                a.setAttempts(a.getAttempts() + 1);
                a.setStatus(SmsAlert.Status.FAILED);                        // numéro ou message refusé : inutile de réessayer
            }
            a.touch();
            log.warn("Alerte {} non envoyée ({}) : statut {}", a.getId(), e.getKind(), a.getStatus());
        }
    }

    // ======================= 4. Suivi de livraison =======================

    /** Interroge TextBee pour les SMS transmis depuis moins de 48 h dont la livraison n'est pas encore connue. */
    public int trackDeliveries(LocalDateTime now) {
        int updated = 0;
        for (SmsAlert sent : alerts.findByStatusAndSmsBatchIdIsNotNullAndSentAtAfter(SmsAlert.Status.SENT, now.minusHours(48))) {
            if ("delivered".equals(sent.getProviderStatus())) continue;
            try {
                List<TextBeeSmsService.MessageStatus> statuses = textBee.batchStatus(sent.getSmsBatchId());
                if (statuses.isEmpty()) continue;
                TextBeeSmsService.MessageStatus m = statuses.stream()
                        .filter(x -> sent.getRecipient() != null && sent.getRecipient().equals(x.recipient()))
                        .findFirst().orElse(statuses.get(0));
                tx.executeWithoutResult(s -> alerts.findById(sent.getId()).ifPresent(a -> {
                    a.setProviderStatus(m.status());
                    a.setDeliveryCheckedAt(now);
                    if ("failed".equals(m.status())) {
                        a.setStatus(SmsAlert.Status.FAILED);
                        a.setLastError("Échec de livraison signalé par le téléphone passerelle TextBee.");
                    }
                    a.touch();
                    alerts.save(a);
                }));
                updated++;
            } catch (TextBeeException e) {
                log.warn("Suivi du lot {} impossible : {}", sent.getSmsBatchId(), e.getKind());
            }
        }
        return updated;
    }

    // ======================= SMS de test =======================

    /** SMS de test vers le numéro de l'utilisateur connecté uniquement (1 par minute). Enregistré et suivi comme une alerte. */
    public SmsAlert sendTest(Long userId, LocalDateTime now) {
        return tx.execute(s -> {
            AppUser user = users.findById(userId).orElseThrow();
            if (user.getPhoneNumber() == null) throw new IllegalArgumentException("Enregistrez d'abord votre numéro de téléphone.");
            if (!alerts.findByUserIdAndKindAndCreatedAtAfter(userId, SmsAlert.Kind.TEST, LocalDateTime.now().minusSeconds(60)).isEmpty()) {
                throw new IllegalArgumentException("Un SMS de test vient d'être envoyé : patientez une minute.");
            }
            SmsAlert a = new SmsAlert(user, null, SmsAlert.Kind.TEST);
            a.setScheduledAt(now);
            a.setMessage("AgriWatt : SMS de test. Vos alertes de coupure d'électricité sont bien configurées.");
            attemptSend(a, user.getPhoneNumber(), now);
            if (a.getStatus() == SmsAlert.Status.PENDING) a.setStatus(SmsAlert.Status.FAILED);   // un test n'est pas réessayé
            return alerts.save(a);
        });
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
