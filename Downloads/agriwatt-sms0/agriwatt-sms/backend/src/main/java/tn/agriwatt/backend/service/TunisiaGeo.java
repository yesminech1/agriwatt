package tn.agriwatt.backend.service;

/**
 * Validation géographique APPROXIMATIVE de la Tunisie (pas une frontière officielle).
 * 1) rectangle large : latitude 30 à 38,5, longitude 7 à 12 ;
 * 2) contour simplifié du territoire (côtes légèrement au large, îles de Djerba et Kerkennah incluses,
 *    marge d'environ 10 km sur les frontières terrestres) pour refuser la mer, l'Algérie ou la Libye.
 * Le même contour est utilisé par la carte du front (app.js, TN_OUTLINE).
 */
public final class TunisiaGeo {

    private TunisiaGeo() {}

    public static final double MIN_LAT = 30.0, MAX_LAT = 38.5, MIN_LON = 7.0, MAX_LON = 12.0;

    /** Sommets {latitude, longitude}, dans l'ordre du contour. */
    static final double[][] OUTLINE = {
            {36.97, 8.60}, {37.10, 9.00}, {37.40, 9.20}, {37.45, 9.85}, {37.30, 10.35}, {37.15, 11.10},
            {36.85, 11.20}, {36.40, 10.75}, {35.85, 10.75}, {35.75, 11.00}, {35.50, 11.15}, {35.20, 11.25},
            {34.95, 11.45}, {34.55, 11.40}, {34.45, 10.65}, {34.20, 10.25}, {33.95, 10.25}, {33.98, 10.75},
            {33.90, 11.15}, {33.55, 11.20}, {33.25, 11.62}, {32.95, 11.62}, {32.50, 11.45}, {32.05, 10.85},
            {31.40, 10.40}, {30.75, 10.30}, {30.15, 9.60}, {30.15, 9.45}, {31.00, 8.95}, {31.60, 8.55},
            {32.40, 8.00}, {33.15, 7.45}, {34.20, 7.45}, {34.65, 8.15}, {35.25, 8.20}, {35.90, 8.20},
            {36.45, 8.10}, {36.80, 8.35}};

    public static boolean inBounds(double lat, double lon) {
        return lat >= MIN_LAT && lat <= MAX_LAT && lon >= MIN_LON && lon <= MAX_LON;
    }

    /** Vrai si le point est dans le rectangle ET dans le contour simplifié (algorithme du lancer de rayon). */
    public static boolean contains(double lat, double lon) {
        if (!inBounds(lat, lon)) return false;
        boolean inside = false;
        for (int i = 0, j = OUTLINE.length - 1; i < OUTLINE.length; j = i++) {
            double yi = OUTLINE[i][0], xi = OUTLINE[i][1], yj = OUTLINE[j][0], xj = OUTLINE[j][1];
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }
}
