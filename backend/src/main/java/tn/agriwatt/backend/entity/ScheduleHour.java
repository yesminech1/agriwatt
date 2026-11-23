package tn.agriwatt.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "schedule_hour", indexes = @Index(name = "idx_schedule_run", columnList = "runId"))
public class ScheduleHour {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long runId;

    @Column(name = "hour_ts", length = 16)
    private String hour;          // "2026-07-15T11:00"

    private boolean pumpOn;
    private String source;        // solar | grid | none
    private double pumpKw;
    private double waterPumpedM3;
    private double waterDeliveredM3;
    private double tankLevelM3;
    private boolean outage;
    private boolean baseline;     // true = planning de référence
    private Double solarKw;       // production solaire prévue (kW) - pour revoir les graphiques sans recalcul
    private Double tempC;         // température prévue

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getHour() { return hour; }
    public void setHour(String hour) { this.hour = hour; }
    public boolean isPumpOn() { return pumpOn; }
    public void setPumpOn(boolean pumpOn) { this.pumpOn = pumpOn; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public double getPumpKw() { return pumpKw; }
    public void setPumpKw(double pumpKw) { this.pumpKw = pumpKw; }
    public double getWaterPumpedM3() { return waterPumpedM3; }
    public void setWaterPumpedM3(double v) { this.waterPumpedM3 = v; }
    public double getWaterDeliveredM3() { return waterDeliveredM3; }
    public void setWaterDeliveredM3(double v) { this.waterDeliveredM3 = v; }
    public double getTankLevelM3() { return tankLevelM3; }
    public void setTankLevelM3(double v) { this.tankLevelM3 = v; }
    public boolean isOutage() { return outage; }
    public void setOutage(boolean outage) { this.outage = outage; }
    public Double getSolarKw() { return solarKw; }
    public void setSolarKw(Double solarKw) { this.solarKw = solarKw; }
    public Double getTempC() { return tempC; }
    public void setTempC(Double tempC) { this.tempC = tempC; }
    public boolean isBaseline() { return baseline; }
    public void setBaseline(boolean baseline) { this.baseline = baseline; }
}