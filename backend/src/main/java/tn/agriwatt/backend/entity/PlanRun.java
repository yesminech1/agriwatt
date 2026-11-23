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
import jakarta.persistence.Table;

/**
 * Un calcul d'optimisation enregistré. Rattaché à une ferme (donc à son propriétaire).
 * Les indicateurs principaux sont en colonnes (liste et filtres de la page Historique) ;
 * le détail heure par heure est dans schedule_hour.
 * Colonnes ajoutées en types objets (nullable) : les anciennes lignes restent valides après la mise à jour du schéma.
 */
@Entity
@Table(name = "plan_run", indexes = @Index(name = "idx_run_farm_date", columnList = "farm_id, createdAt"))
public class PlanRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime createdAt = LocalDateTime.now();

    /** Ferme concernée (null uniquement pour les calculs enregistrés avant la gestion des fermes). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id")
    private Farm farm;

    @Column(columnDefinition = "LONGTEXT")
    private String configJson;

    @Column(columnDefinition = "LONGTEXT")
    private String kpisJson;

    // --- contexte du calcul ---
    private Double lat;
    private Double lon;
    @Column(length = 20)
    private String crop;
    private Double areaM2;
    private Integer days;
    @Column(length = 16)
    private String forecastStart;
    @Column(length = 16)
    private String forecastEnd;
    @Column(length = 20)
    private String weatherSource;

    // --- résultats (plan optimisé) ---
    private Double solarKwh;
    private Double gridKwh;
    private Double pumpEnergyKwh;
    private Double waterNeedM3;
    private Double waterDeliveredM3;
    private Double minTankLevelM3;
    private Double finalTankLevelM3;

    // --- KPI ---
    private Double savingsTnd;
    private Double costOptimizedTnd;
    private Double costBaselineTnd;
    private Double solarSharePct;
    private Double gridPeakKwhAvoided;
    private Integer outageHoursCovered;
    private Integer baselineOutageHoursCovered;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Farm getFarm() { return farm; }
    public void setFarm(Farm farm) { this.farm = farm; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getKpisJson() { return kpisJson; }
    public void setKpisJson(String kpisJson) { this.kpisJson = kpisJson; }
    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }
    public Double getLon() { return lon; }
    public void setLon(Double lon) { this.lon = lon; }
    public String getCrop() { return crop; }
    public void setCrop(String crop) { this.crop = crop; }
    public Double getAreaM2() { return areaM2; }
    public void setAreaM2(Double areaM2) { this.areaM2 = areaM2; }
    public Integer getDays() { return days; }
    public void setDays(Integer days) { this.days = days; }
    public String getForecastStart() { return forecastStart; }
    public void setForecastStart(String forecastStart) { this.forecastStart = forecastStart; }
    public String getForecastEnd() { return forecastEnd; }
    public void setForecastEnd(String forecastEnd) { this.forecastEnd = forecastEnd; }
    public String getWeatherSource() { return weatherSource; }
    public void setWeatherSource(String weatherSource) { this.weatherSource = weatherSource; }
    public Double getSolarKwh() { return solarKwh; }
    public void setSolarKwh(Double solarKwh) { this.solarKwh = solarKwh; }
    public Double getGridKwh() { return gridKwh; }
    public void setGridKwh(Double gridKwh) { this.gridKwh = gridKwh; }
    public Double getPumpEnergyKwh() { return pumpEnergyKwh; }
    public void setPumpEnergyKwh(Double pumpEnergyKwh) { this.pumpEnergyKwh = pumpEnergyKwh; }
    public Double getWaterNeedM3() { return waterNeedM3; }
    public void setWaterNeedM3(Double waterNeedM3) { this.waterNeedM3 = waterNeedM3; }
    public Double getWaterDeliveredM3() { return waterDeliveredM3; }
    public void setWaterDeliveredM3(Double waterDeliveredM3) { this.waterDeliveredM3 = waterDeliveredM3; }
    public Double getMinTankLevelM3() { return minTankLevelM3; }
    public void setMinTankLevelM3(Double minTankLevelM3) { this.minTankLevelM3 = minTankLevelM3; }
    public Double getFinalTankLevelM3() { return finalTankLevelM3; }
    public void setFinalTankLevelM3(Double finalTankLevelM3) { this.finalTankLevelM3 = finalTankLevelM3; }
    public Double getSavingsTnd() { return savingsTnd; }
    public void setSavingsTnd(Double savingsTnd) { this.savingsTnd = savingsTnd; }
    public Double getCostOptimizedTnd() { return costOptimizedTnd; }
    public void setCostOptimizedTnd(Double costOptimizedTnd) { this.costOptimizedTnd = costOptimizedTnd; }
    public Double getCostBaselineTnd() { return costBaselineTnd; }
    public void setCostBaselineTnd(Double costBaselineTnd) { this.costBaselineTnd = costBaselineTnd; }
    public Double getSolarSharePct() { return solarSharePct; }
    public void setSolarSharePct(Double solarSharePct) { this.solarSharePct = solarSharePct; }
    public Double getGridPeakKwhAvoided() { return gridPeakKwhAvoided; }
    public void setGridPeakKwhAvoided(Double gridPeakKwhAvoided) { this.gridPeakKwhAvoided = gridPeakKwhAvoided; }
    public Integer getOutageHoursCovered() { return outageHoursCovered; }
    public void setOutageHoursCovered(Integer outageHoursCovered) { this.outageHoursCovered = outageHoursCovered; }
    public Integer getBaselineOutageHoursCovered() { return baselineOutageHoursCovered; }
    public void setBaselineOutageHoursCovered(Integer v) { this.baselineOutageHoursCovered = v; }
}
