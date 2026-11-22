package tn.agriwatt.backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** Ferme d'un utilisateur : paramètres réutilisés par POST /api/plan. Une ferme appartient à un seul utilisateur. */
@Entity
@Table(name = "farm", indexes = @Index(name = "idx_farm_user", columnList = "user_id"))
public class Farm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Propriétaire : fixé côté serveur à partir de l'utilisateur connecté, jamais depuis le JSON. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false, length = 120)
    private String name;

    private double lat;
    private double lon;

    @Column(nullable = false, length = 20)
    private String crop;

    private double areaM2;
    private double panelAreaM2;
    private double panelEfficiency;
    private double pumpPowerKw;
    private double pumpFlowM3h;
    private double tankCapacityM3;
    private double tankInitialM3;

    /** null = pas de coupure prévue. */
    private Integer outageStartHour;
    private Integer outageEndHour;

    private int days;
    private double offPeakTariff;
    private double normalTariff;
    private double peakTariff;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    protected Farm() {}

    public Farm(AppUser user) {
        this.user = user;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public AppUser getUser() { return user; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public double getLat() { return lat; }
    public void setLat(double lat) { this.lat = lat; }
    public double getLon() { return lon; }
    public void setLon(double lon) { this.lon = lon; }
    public String getCrop() { return crop; }
    public void setCrop(String crop) { this.crop = crop; }
    public double getAreaM2() { return areaM2; }
    public void setAreaM2(double areaM2) { this.areaM2 = areaM2; }
    public double getPanelAreaM2() { return panelAreaM2; }
    public void setPanelAreaM2(double panelAreaM2) { this.panelAreaM2 = panelAreaM2; }
    public double getPanelEfficiency() { return panelEfficiency; }
    public void setPanelEfficiency(double panelEfficiency) { this.panelEfficiency = panelEfficiency; }
    public double getPumpPowerKw() { return pumpPowerKw; }
    public void setPumpPowerKw(double pumpPowerKw) { this.pumpPowerKw = pumpPowerKw; }
    public double getPumpFlowM3h() { return pumpFlowM3h; }
    public void setPumpFlowM3h(double pumpFlowM3h) { this.pumpFlowM3h = pumpFlowM3h; }
    public double getTankCapacityM3() { return tankCapacityM3; }
    public void setTankCapacityM3(double tankCapacityM3) { this.tankCapacityM3 = tankCapacityM3; }
    public double getTankInitialM3() { return tankInitialM3; }
    public void setTankInitialM3(double tankInitialM3) { this.tankInitialM3 = tankInitialM3; }
    public Integer getOutageStartHour() { return outageStartHour; }
    public void setOutageStartHour(Integer outageStartHour) { this.outageStartHour = outageStartHour; }
    public Integer getOutageEndHour() { return outageEndHour; }
    public void setOutageEndHour(Integer outageEndHour) { this.outageEndHour = outageEndHour; }
    public int getDays() { return days; }
    public void setDays(int days) { this.days = days; }
    public double getOffPeakTariff() { return offPeakTariff; }
    public void setOffPeakTariff(double offPeakTariff) { this.offPeakTariff = offPeakTariff; }
    public double getNormalTariff() { return normalTariff; }
    public void setNormalTariff(double normalTariff) { this.normalTariff = normalTariff; }
    public double getPeakTariff() { return peakTariff; }
    public void setPeakTariff(double peakTariff) { this.peakTariff = peakTariff; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
