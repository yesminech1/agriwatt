package tn.agriwatt.backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * Alerte SMS enregistrée en base (survit aux redémarrages). Heures en heure de Tunisie.
 * Unicité (ferme, début de coupure) : une seule alerte par coupure prévue -> pas de doublon.
 * status = état côté AgriWatt ; providerStatus = suivi de livraison renvoyé par TextBee (pending, sent, delivered, failed…).
 */
@Entity
@Table(name = "sms_alert",
        uniqueConstraints = @UniqueConstraint(name = "uk_alert_farm_outage", columnNames = {"farm_id", "outage_start"}),
        indexes = {@Index(name = "idx_alert_status_due", columnList = "status, scheduled_at"),
                   @Index(name = "idx_alert_user", columnList = "user_id")})
public class SmsAlert {

    public enum Status { PENDING, SENT, FAILED, CANCELLED }
    public enum Kind { OUTAGE, TEST }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    /** null pour un SMS de test. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id")
    private Farm farm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind = Kind.OUTAGE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.PENDING;

    @Column(name = "outage_start")
    private LocalDateTime outageStart;
    private LocalDateTime outageEnd;
    private Double riskMax;
    private Boolean plannedOutage;          // true = horaires de coupure saisis pour la ferme ; false = risque estimé (proxy)

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;      // début de coupure - 2 h
    private LocalDateTime nextAttemptAt;    // nouvel essai après une erreur temporaire

    @Column(length = 500, nullable = false)
    private String message;

    @Column(length = 20)
    private String recipient;               // numéro utilisé au moment de l'envoi

    @Column(length = 64)
    private String smsBatchId;
    @Column(length = 20)
    private String providerStatus;
    private LocalDateTime deliveryCheckedAt;

    private int attempts;
    @Column(length = 500)
    private String lastError;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime sentAt;
    private LocalDateTime updatedAt = LocalDateTime.now();

    protected SmsAlert() {}

    public SmsAlert(AppUser user, Farm farm, Kind kind) {
        this.user = user;
        this.farm = farm;
        this.kind = kind;
    }

    public Long getId() { return id; }
    public AppUser getUser() { return user; }
    public Farm getFarm() { return farm; }
    public Kind getKind() { return kind; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; this.updatedAt = LocalDateTime.now(); }
    public LocalDateTime getOutageStart() { return outageStart; }
    public void setOutageStart(LocalDateTime outageStart) { this.outageStart = outageStart; }
    public LocalDateTime getOutageEnd() { return outageEnd; }
    public void setOutageEnd(LocalDateTime outageEnd) { this.outageEnd = outageEnd; }
    public Double getRiskMax() { return riskMax; }
    public void setRiskMax(Double riskMax) { this.riskMax = riskMax; }
    public Boolean getPlannedOutage() { return plannedOutage; }
    public void setPlannedOutage(Boolean plannedOutage) { this.plannedOutage = plannedOutage; }
    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(LocalDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }
    public String getSmsBatchId() { return smsBatchId; }
    public void setSmsBatchId(String smsBatchId) { this.smsBatchId = smsBatchId; }
    public String getProviderStatus() { return providerStatus; }
    public void setProviderStatus(String providerStatus) { this.providerStatus = providerStatus; }
    public LocalDateTime getDeliveryCheckedAt() { return deliveryCheckedAt; }
    public void setDeliveryCheckedAt(LocalDateTime deliveryCheckedAt) { this.deliveryCheckedAt = deliveryCheckedAt; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError == null || lastError.length() <= 500 ? lastError : lastError.substring(0, 500); }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = LocalDateTime.now(); }
}
