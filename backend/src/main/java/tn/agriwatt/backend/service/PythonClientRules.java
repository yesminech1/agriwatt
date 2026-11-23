package tn.agriwatt.backend.service;

import tn.agriwatt.backend.dto.PlanRequest;

/** Traduction des paramètres de la ferme vers le contrat du service Python. */
public final class PythonClientRules {

    private PythonClientRules() {}

    /** Coupure prévue exploitable par le service Python (0 <= début < fin <= 24). */
    public static boolean hasOutage(PlanRequest r) {
        Integer s = r.outageStartHour(), e = r.outageEndHour();
        return s != null && e != null && s >= 0 && e <= 24 && s < e;
    }
}
