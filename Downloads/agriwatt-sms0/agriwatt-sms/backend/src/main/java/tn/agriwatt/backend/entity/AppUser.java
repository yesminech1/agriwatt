package tn.agriwatt.backend.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/** Compte utilisateur dans la table historique partagée. Seul le hash BCrypt est stocké. */
@Entity
@Table(name = "`user`")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Numéro au format international (+216XXXXXXXX) pour les alertes SMS ; facultatif. */
    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    /** Alertes SMS de coupure activées (désactivées par défaut). */
    @Column(name = "sms_alerts_enabled")
    private Boolean smsAlertsEnabled = false;

    /** Fermes de l'utilisateur (côté inverse, lecture seule ; jamais sérialisé en JSON). */
    @JsonIgnore
    @OneToMany(mappedBy = "user")
    private List<Farm> farms = new ArrayList<>();

    protected AppUser() {}

    public AppUser(String fullName, String email, String passwordHash) {
        this.fullName = fullName;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public Long getId() { return id; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public List<Farm> getFarms() { return farms; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public boolean isSmsAlertsEnabled() { return Boolean.TRUE.equals(smsAlertsEnabled); }
    public void setSmsAlertsEnabled(boolean enabled) { this.smsAlertsEnabled = enabled; }
}
