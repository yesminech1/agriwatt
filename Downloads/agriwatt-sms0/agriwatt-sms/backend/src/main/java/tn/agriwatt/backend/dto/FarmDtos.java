package tn.agriwatt.backend.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Échanges JSON de /api/farms (camelCase). Aucun champ propriétaire : il vient de la session. */
public final class FarmDtos {
    private FarmDtos() {}

    /** Corps de POST (création) et PUT (modification). */
    public record FarmRequest(
            @NotBlank(message = "Le nom de la ferme est obligatoire.")
            @Size(max = 120, message = "Le nom ne doit pas dépasser 120 caractères.") String name,
            @NotNull(message = "Sélectionnez l'emplacement de la ferme sur la carte.")
            @DecimalMin(value = "30.0", message = "La latitude doit être comprise entre 30 et 38,5 (Tunisie).")
            @DecimalMax(value = "38.5", message = "La latitude doit être comprise entre 30 et 38,5 (Tunisie).") Double lat,
            @NotNull(message = "Sélectionnez l'emplacement de la ferme sur la carte.")
            @DecimalMin(value = "7.0", message = "La longitude doit être comprise entre 7 et 12 (Tunisie).")
            @DecimalMax(value = "12.0", message = "La longitude doit être comprise entre 7 et 12 (Tunisie).") Double lon,
            @NotNull(message = "La culture est obligatoire.")
            @Pattern(regexp = "olive|datte|tomate|ble", message = "Culture invalide (olive, datte, tomate ou ble).") String crop,
            @NotNull(message = "La surface est obligatoire.")
            @Positive(message = "La surface agricole doit être strictement positive.") Double areaM2,
            @NotNull(message = "La surface des panneaux est obligatoire.")
            @PositiveOrZero(message = "La surface des panneaux doit être positive ou nulle.") Double panelAreaM2,
            @NotNull(message = "Le rendement est obligatoire.")
            @DecimalMin(value = "0", inclusive = false, message = "Le rendement doit être compris entre 0 et 1.")
            @DecimalMax(value = "1", message = "Le rendement doit être compris entre 0 et 1.") Double panelEfficiency,
            @NotNull(message = "La puissance de la pompe est obligatoire.")
            @Positive(message = "La puissance de la pompe doit être strictement positive.") Double pumpPowerKw,
            @NotNull(message = "Le débit de la pompe est obligatoire.")
            @Positive(message = "Le débit de la pompe doit être strictement positif.") Double pumpFlowM3h,
            @NotNull(message = "La capacité du réservoir est obligatoire.")
            @Positive(message = "La capacité du réservoir doit être strictement positive.") Double tankCapacityM3,
            @NotNull(message = "Le niveau initial est obligatoire.")
            @PositiveOrZero(message = "Le niveau initial doit être positif ou nul.") Double tankInitialM3,
            @Min(value = 0, message = "L'heure de début doit être comprise entre 0 et 23.")
            @Max(value = 23, message = "L'heure de début doit être comprise entre 0 et 23.") Integer outageStartHour,
            @Min(value = 0, message = "L'heure de fin doit être comprise entre 0 et 23.")
            @Max(value = 23, message = "L'heure de fin doit être comprise entre 0 et 23.") Integer outageEndHour,
            @NotNull(message = "Le nombre de jours est obligatoire.")
            @Min(value = 1, message = "Le nombre de jours doit être compris entre 1 et 7.")
            @Max(value = 7, message = "Le nombre de jours doit être compris entre 1 et 7.") Integer days,
            @NotNull(message = "Le tarif heures creuses est obligatoire.")
            @PositiveOrZero(message = "Le tarif heures creuses doit être positif ou nul.") Double offPeakTariff,
            @NotNull(message = "Le tarif normal est obligatoire.")
            @PositiveOrZero(message = "Le tarif normal doit être positif ou nul.") Double normalTariff,
            @NotNull(message = "Le tarif de pointe est obligatoire.")
            @PositiveOrZero(message = "Le tarif de pointe doit être positif ou nul.") Double peakTariff) {}

    /** Réponse : paramètres de la ferme, sans l'utilisateur (pas de boucle JSON, pas de fuite). */
    public record FarmResponse(
            Long id, String name, double lat, double lon, String crop,
            double areaM2, double panelAreaM2, double panelEfficiency,
            double pumpPowerKw, double pumpFlowM3h, double tankCapacityM3, double tankInitialM3,
            Integer outageStartHour, Integer outageEndHour, int days,
            double offPeakTariff, double normalTariff, double peakTariff,
            LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
