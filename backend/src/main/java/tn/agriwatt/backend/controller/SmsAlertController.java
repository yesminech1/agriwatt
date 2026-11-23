package tn.agriwatt.backend.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tn.agriwatt.backend.dto.SmsDtos.AlertSettings;
import tn.agriwatt.backend.dto.SmsDtos.AlertSettingsUpdate;
import tn.agriwatt.backend.dto.SmsDtos.AlertView;
import tn.agriwatt.backend.entity.AppUser;
import tn.agriwatt.backend.entity.SmsAlert;
import tn.agriwatt.backend.repository.AppUserRepository;
import tn.agriwatt.backend.repository.SmsAlertRepository;
import tn.agriwatt.backend.service.OutageAlertService;
import tn.agriwatt.backend.service.PhoneNumbers;
import tn.agriwatt.backend.service.TextBeeSmsService;

/** Alertes SMS de l'utilisateur connecté (numéro, activation, test, historique) et test de connexion TextBee. */
@RestController
@RequestMapping("/api")
public class SmsAlertController {

    private final AppUserRepository users;
    private final SmsAlertRepository alerts;
    private final OutageAlertService outageAlerts;
    private final TextBeeSmsService textBee;

    public SmsAlertController(AppUserRepository users, SmsAlertRepository alerts,
                              OutageAlertService outageAlerts, TextBeeSmsService textBee) {
        this.users = users;
        this.alerts = alerts;
        this.outageAlerts = outageAlerts;
        this.textBee = textBee;
    }

    private AppUser me(Authentication auth) {
        return users.findByEmail(auth.getName()).orElseThrow(() -> new IllegalArgumentException("Utilisateur inconnu."));
    }

    @GetMapping("/account/alerts")
    public AlertSettings settings(Authentication auth) {
        AppUser u = me(auth);
        return new AlertSettings(u.getPhoneNumber(), u.isSmsAlertsEnabled(), textBee.isConfigured(), outageAlerts.getLeadHours());
    }

    /** Modifier le numéro et/ou activer / désactiver les alertes. */
    @PutMapping("/account/alerts")
    public AlertSettings update(@RequestBody AlertSettingsUpdate req, Authentication auth) {
        AppUser u = me(auth);
        String phone = PhoneNumbers.normalize(req.phoneNumber());          // 400 si invalide
        boolean enabled = req.smsAlertsEnabled() == null ? u.isSmsAlertsEnabled() : req.smsAlertsEnabled();
        if (enabled && phone == null) {
            throw new IllegalArgumentException("Indiquez un numéro de téléphone pour activer les alertes SMS.");
        }
        u.setPhoneNumber(phone);
        u.setSmsAlertsEnabled(enabled);
        users.save(u);
        if (!enabled) outageAlerts.cancelPendingForUser(u.getId(), "Alertes SMS désactivées par l'utilisateur.");
        return new AlertSettings(u.getPhoneNumber(), u.isSmsAlertsEnabled(), textBee.isConfigured(), outageAlerts.getLeadHours());
    }

    /** SMS de test vers SON numéro uniquement. */
    @PostMapping("/account/alerts/test")
    public AlertView test(Authentication auth) {
        return view(outageAlerts.sendTest(me(auth).getId(), OutageAlertService.now()));
    }

    /** 50 dernières alertes de l'utilisateur (les statuts de livraison sont mis à jour par la tâche de suivi). */
    @GetMapping("/account/alerts/history")
    @Transactional(readOnly = true)
    public List<AlertView> history(Authentication auth) {
        return alerts.findTop50ByUserIdOrderByCreatedAtDesc(me(auth).getId()).stream().map(SmsAlertController::view).toList();
    }

    /** Test de connexion à TextBee (jamais la clé). */
    @GetMapping("/sms/status")
    public TextBeeSmsService.GatewayStatus gateway() {
        return textBee.gatewayStatus();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
    }

    static AlertView view(SmsAlert a) {
        return new AlertView(a.getId(), a.getKind().name(), a.getStatus().name(),
                a.getFarm() == null ? null : a.getFarm().getId(), a.getFarm() == null ? null : a.getFarm().getName(),
                str(a.getOutageStart()), str(a.getOutageEnd()), a.getPlannedOutage(), a.getRiskMax(),
                str(a.getScheduledAt()), str(a.getSentAt()), PhoneNumbers.mask(a.getRecipient()), a.getSmsBatchId(),
                a.getProviderStatus(), a.getAttempts(), a.getLastError(), a.getMessage(), str(a.getCreatedAt()));
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
