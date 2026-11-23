package tn.agriwatt.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import tn.agriwatt.backend.service.OutageAlertService;

/**
 * Tâches planifiées des alertes SMS. Les alertes sont en base : après un redémarrage, les alertes PENDING
 * dont l'heure est passée sont envoyées au premier passage (si la coupure n'a pas encore commencé).
 * Désactivable avec agriwatt.alerts.scheduler-enabled=false (utilisé par les tests).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agriwatt.alerts.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class AlertSchedulingConfig {

    private static final Logger log = LoggerFactory.getLogger(AlertSchedulingConfig.class);
    private final OutageAlertService alerts;

    public AlertSchedulingConfig(OutageAlertService alerts) {
        this.alerts = alerts;
    }

    /** Envoi des alertes arrivées à échéance (toutes les minutes). */
    @Scheduled(fixedDelayString = "${agriwatt.alerts.dispatch-delay-ms:60000}", initialDelayString = "${agriwatt.alerts.initial-delay-ms:15000}")
    public void dispatch() {
        safe("envoi", () -> alerts.dispatchDue(OutageAlertService.now()));
    }

    /** Suivi de livraison des SMS transmis (toutes les 5 minutes). */
    @Scheduled(fixedDelayString = "${agriwatt.alerts.tracking-delay-ms:300000}", initialDelayString = "${agriwatt.alerts.initial-delay-ms:15000}")
    public void track() {
        safe("suivi", () -> alerts.trackDeliveries(OutageAlertService.now()));
    }

    /** Nouvelle prédiction des coupures pour les fermes des utilisateurs abonnés (par défaut toutes les 3 heures). */
    @Scheduled(cron = "${agriwatt.alerts.refresh-cron:0 5 */3 * * *}", zone = "Africa/Tunis")
    public void refresh() {
        safe("prévision", () -> { alerts.refreshAllPredictions(OutageAlertService.now()); return 0; });
    }

    private void safe(String what, java.util.concurrent.Callable<Integer> job) {
        try {
            job.call();
        } catch (Exception e) {
            log.warn("Tâche d'alertes ({}) en erreur : {}", what, e.getMessage());
        }
    }
}
