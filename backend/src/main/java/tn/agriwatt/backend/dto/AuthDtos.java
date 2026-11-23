package tn.agriwatt.backend.dto;

/** Corps des requêtes / réponses de /api/auth (travail C). */
public final class AuthDtos {
    private AuthDtos() {}

    /** phoneNumber (facultatif, format +216XXXXXXXX) et smsAlerts (opt-in) : alertes SMS de coupure. */
    public record RegisterRequest(String fullName, String email, String password, String phoneNumber, Boolean smsAlerts) {}
    public record LoginRequest(String email, String password) {}
    public record UserResponse(Long id, String fullName, String email, String phoneNumber, boolean smsAlertsEnabled) {}
}
