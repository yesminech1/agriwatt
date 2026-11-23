package tn.agriwatt.backend.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import tn.agriwatt.backend.dto.DetectionDtos.AnalysisView;
import tn.agriwatt.backend.dto.DetectionDtos.PythonAnalysis;
import tn.agriwatt.backend.entity.AppUser;
import tn.agriwatt.backend.entity.PlantAnalysis;
import tn.agriwatt.backend.repository.AppUserRepository;
import tn.agriwatt.backend.repository.PlantAnalysisRepository;

/**
 * Détection du stress hydrique : validation du fichier, appel au modèle, conservation de l'image sur disque
 * (jamais en base) et historique propre à chaque utilisateur. L'utilisateur vient TOUJOURS de la session.
 */
@Service
public class PlantDetectionService {

    private static final Logger log = LoggerFactory.getLogger(PlantDetectionService.class);
    public static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    /** Plantes réellement présentes dans le dataset d'entraînement. */
    static final Map<String, String> PLANTS = Map.of("olivier", "Olivier");
    static final Map<String, String> LABELS = Map.of(
            "SANS_STRESS", "Aucun stress hydrique détecté",
            "STRESS_HYDRIQUE", "Signes compatibles avec un stress hydrique",
            "INCERTAIN", "Résultat incertain");

    private final PlantAnalysisRepository analyses;
    private final AppUserRepository users;
    private final PlantDetectionClient model;
    private final Path storageDir;

    public PlantDetectionService(PlantAnalysisRepository analyses, AppUserRepository users, PlantDetectionClient model,
                                 @Value("${agriwatt.detection.storage-dir:uploads/plant-analyses}") String storageDir) {
        this.analyses = analyses;
        this.users = users;
        this.model = model;
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
    }

    public record StoredImage(byte[] bytes, String contentType) {}

    public static class AnalysisNotFoundException extends RuntimeException {
        public AnalysisNotFoundException() { super("Analyse introuvable."); }
    }

    /** Pas de transaction autour de l'appel au modèle : l'enregistrement final a la sienne (saveAndFlush). */
    public AnalysisView analyze(String email, MultipartFile file, String plantType) {
        AppUser user = user(email);
        String plant = plantType == null ? "" : plantType.trim().toLowerCase(Locale.ROOT);
        if (!PLANTS.containsKey(plant)) {
            throw new IllegalArgumentException("Plante non prise en charge : le modèle ne reconnaît que l'olivier.");
        }
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Sélectionnez une image à analyser.");
        if (file.getSize() > MAX_IMAGE_BYTES) throw new IllegalArgumentException("Image trop volumineuse (maximum 10 Mo).");
        String name = safeName(file.getOriginalFilename());
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!ext.isEmpty() && !List.of("jpg", "jpeg", "png").contains(ext)) {
            throw new IllegalArgumentException("Format non accepté : seules les images JPG, JPEG et PNG sont acceptées.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Lecture du fichier impossible. Sélectionnez une autre image.");
        }
        String contentType = sniff(bytes);
        if (contentType == null) {
            throw new IllegalArgumentException("Le fichier n'est pas une image JPG ou PNG valide.");
        }

        PythonAnalysis r = model.analyze(bytes, name, contentType, plant);   // résultat réel du modèle

        String imageRef = store(bytes, contentType);
        PlantAnalysis a = new PlantAnalysis();
        a.setUser(user);
        a.setPlantType(plant);
        a.setPrediction(r.prediction());
        a.setModelClass(r.modelClass());
        a.setConfidence(r.confidence());
        a.setProbStress(r.probabilities().get("STRESS_HYDRIQUE"));
        a.setUncertainThreshold(r.uncertainThreshold());
        a.setInterpretation(cut(r.interpretation(), 1000));
        a.setRecommendation(cut(r.recommendation(), 1000));
        a.setModelVersion(cut(r.modelVersion(), 40));
        a.setImageName(cut(name, 255));
        a.setImageRef(imageRef);
        a.setImageContentType(imageRef == null ? null : contentType);
        a.setImageSize((long) bytes.length);
        a.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        try {
            return view(analyses.saveAndFlush(a));
        } catch (RuntimeException e) {
            deleteFile(imageRef);                      // pas d'image orpheline si l'enregistrement échoue
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<AnalysisView> list(String email) {
        return analyses.findByUserIdOrderByCreatedAtDescIdDesc(user(email).getId()).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public AnalysisView get(String email, Long id) {
        return view(owned(email, id));
    }

    @Transactional(readOnly = true)
    public StoredImage image(String email, Long id) {
        PlantAnalysis a = owned(email, id);
        Path p = resolve(a.getImageRef());
        if (p == null || !Files.isRegularFile(p)) throw new AnalysisNotFoundException();
        try {
            return new StoredImage(Files.readAllBytes(p), a.getImageContentType());
        } catch (IOException e) {
            throw new AnalysisNotFoundException();
        }
    }

    @Transactional
    public void delete(String email, Long id) {
        PlantAnalysis a = owned(email, id);
        analyses.delete(a);
        deleteFile(a.getImageRef());
    }

    // ---------- interne ----------
    private AppUser user(String email) {
        return users.findByEmail(email).orElseThrow(() -> new IllegalStateException("Utilisateur connecté introuvable"));
    }

    /** Une analyse d'un autre utilisateur est traitée comme inexistante (404). */
    private PlantAnalysis owned(String email, Long id) {
        return analyses.findByIdAndUserId(id, user(email).getId()).orElseThrow(AnalysisNotFoundException::new);
    }

    private AnalysisView view(PlantAnalysis a) {
        boolean hasImage = a.getImageRef() != null;
        return new AnalysisView(a.getId(), a.getPlantType(), PLANTS.getOrDefault(a.getPlantType(), a.getPlantType()),
                a.getPrediction(), LABELS.getOrDefault(a.getPrediction(), a.getPrediction()), a.getModelClass(),
                a.getConfidence(), a.getProbStress(), round4(1 - a.getProbStress()), a.getUncertainThreshold(),
                a.getInterpretation(), a.getRecommendation(), a.getModelVersion(), a.getImageName(), hasImage,
                hasImage ? "/api/detection/analyses/" + a.getId() + "/image" : null, a.getCreatedAt().toString());
    }

    /** Vrai type du fichier d'après ses premiers octets (l'extension et le Content-Type peuvent mentir). */
    static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) return "image/png";
        return null;
    }

    private String store(byte[] bytes, String contentType) {
        String ref = UUID.randomUUID() + (contentType.equals("image/png") ? ".png" : ".jpg");
        try {
            Files.createDirectories(storageDir);
            Files.write(storageDir.resolve(ref), bytes);
            return ref;
        } catch (IOException e) {
            log.warn("Image d'analyse non conservée ({}) : l'analyse est enregistrée sans image", e.getClass().getSimpleName());
            return null;
        }
    }

    private Path resolve(String ref) {
        if (ref == null) return null;
        Path p = storageDir.resolve(ref).normalize();
        return p.startsWith(storageDir) ? p : null;
    }

    private void deleteFile(String ref) {
        Path p = resolve(ref);
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Image {} non supprimée", ref);
        }
    }

    private static String safeName(String original) {
        if (original == null || original.isBlank()) return "image";
        String n = original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|]", "_").trim();
        return n.isEmpty() ? "image" : n;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static double round4(double v) {
        return Math.round(v * 10000d) / 10000d;
    }
}
