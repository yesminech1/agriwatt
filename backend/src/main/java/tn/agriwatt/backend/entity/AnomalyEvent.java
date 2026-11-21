package tn.agriwatt.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "anomaly_event")
public class AnomalyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_ts", length = 32)
    private String timestamp;

    private double powerKw;
    private boolean anomaly;
    private double score;

    @Column(length = 500)
    private String reason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public double getPowerKw() { return powerKw; }
    public void setPowerKw(double powerKw) { this.powerKw = powerKw; }
    public boolean isAnomaly() { return anomaly; }
    public void setAnomaly(boolean anomaly) { this.anomaly = anomaly; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}