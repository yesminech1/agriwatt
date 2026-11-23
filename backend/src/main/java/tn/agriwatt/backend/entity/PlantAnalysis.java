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
 * Analyse d'une photo de plante par le modèle de stress hydrique. L'image n'est PAS stockée en base :
 * seul son nom de fichier (imageRef) dans le dossier agriwatt.detection.storage-dir est enregistré.
 */
@Entity
@Table(name = "plant_analysis", indexes = @Index(name = "idx_plant_analysis_user", columnList = "user_id, created_at"))
public class PlantAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "plant_type", length = 40, nullable = false)
    private String plantType;

    /** SANS_STRESS, STRESS_HYDRIQUE ou INCERTAIN (score sous le seuil). */
    @Column(length = 20, nullable = false)
    private String prediction;

    /** Classe la plus probable selon le modèle, même quand le résultat est incertain. */
    @Column(name = "model_class", length = 20, nullable = false)
    private String modelClass;

    @Column(nullable = false)
    private double confidence;

    @Column(name = "prob_stress", nullable = false)
    private double probStress;

    @Column(name = "uncertain_threshold")
    private Double uncertainThreshold;

    @Column(length = 1000)
    private String interpretation;

    @Column(length = 1000)
    private String recommendation;

    @Column(name = "model_version", length = 40)
    private String modelVersion;

    @Column(name = "image_name", length = 255)
    private String imageName;

    @Column(name = "image_ref", length = 80)
    private String imageRef;

    @Column(name = "image_content_type", length = 40)
    private String imageContentType;

    @Column(name = "image_size")
    private Long imageSize;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }
    public String getPlantType() { return plantType; }
    public void setPlantType(String plantType) { this.plantType = plantType; }
    public String getPrediction() { return prediction; }
    public void setPrediction(String prediction) { this.prediction = prediction; }
    public String getModelClass() { return modelClass; }
    public void setModelClass(String modelClass) { this.modelClass = modelClass; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public double getProbStress() { return probStress; }
    public void setProbStress(double probStress) { this.probStress = probStress; }
    public Double getUncertainThreshold() { return uncertainThreshold; }
    public void setUncertainThreshold(Double uncertainThreshold) { this.uncertainThreshold = uncertainThreshold; }
    public String getInterpretation() { return interpretation; }
    public void setInterpretation(String interpretation) { this.interpretation = interpretation; }
    public String getRecommendation() { return recommendation; }
    public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public String getImageName() { return imageName; }
    public void setImageName(String imageName) { this.imageName = imageName; }
    public String getImageRef() { return imageRef; }
    public void setImageRef(String imageRef) { this.imageRef = imageRef; }
    public String getImageContentType() { return imageContentType; }
    public void setImageContentType(String imageContentType) { this.imageContentType = imageContentType; }
    public Long getImageSize() { return imageSize; }
    public void setImageSize(Long imageSize) { this.imageSize = imageSize; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
