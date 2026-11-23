package tn.agriwatt.backend;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tn.agriwatt.backend.dto.PlanRequest;
import tn.agriwatt.backend.service.PythonClientRules;

/** Règles de traduction front -> service Python (sans Spring). */
class PythonClientRulesTests {

    private static PlanRequest withOutage(Integer start, Integer end) {
        PlanRequest d = PlanRequest.defaults();
        return new PlanRequest(d.lat(), d.lon(), d.crop(), d.areaM2(), d.panelAreaM2(), d.panelEfficiency(),
                d.pumpPowerKw(), d.pumpFlowM3h(), d.tankCapacityM3(), d.tankInitialM3(),
                start, end, d.days(), d.tariff(), null);
    }

    @Test
    void coupureTransmiseSeulementSiValide() {
        assertTrue(PythonClientRules.hasOutage(withOutage(14, 19)));
        assertFalse(PythonClientRules.hasOutage(withOutage(0, 0)));     // coupure désactivée par le front
        assertFalse(PythonClientRules.hasOutage(withOutage(null, null)));
        assertFalse(PythonClientRules.hasOutage(withOutage(20, 2)));    // plage de nuit : gérée localement par B
    }
}
