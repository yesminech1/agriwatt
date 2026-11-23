package tn.agriwatt.backend.dto;

/** Alertes SMS : réglages du compte et historique (aucune clé ni donnée TextBee sensible n'est exposée). */
public final class SmsDtos {
    private SmsDtos() {}

    public record AlertSettings(String phoneNumber, boolean smsAlertsEnabled, boolean smsGatewayConfigured, int leadHours) {}
    public record AlertSettingsUpdate(String phoneNumber, Boolean smsAlertsEnabled) {}

    public record AlertView(Long id, String kind, String status, Long farmId, String farmName,
                            String outageStart, String outageEnd, Boolean plannedOutage, Double riskMax,
                            String scheduledAt, String sentAt, String recipient, String smsBatchId,
                            String providerStatus, int attempts, String lastError, String message, String createdAt) {}
}
