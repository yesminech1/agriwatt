package tn.agriwatt.backend.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tn.agriwatt.backend.dto.PlanRequest;
import tn.agriwatt.backend.dto.PlanResponse;
import tn.agriwatt.backend.dto.RunDtos.RunDetail;
import tn.agriwatt.backend.dto.RunDtos.RunSummary;
import tn.agriwatt.backend.service.HistoryService;
import tn.agriwatt.backend.service.PlanService;

/** Calculs d'optimisation et historique. L'utilisateur est toujours celui de la session. */
@RestController
@RequestMapping("/api")
public class PlanController {

    private final PlanService planService;
    private final HistoryService history;

    public PlanController(PlanService planService, HistoryService history) {
        this.planService = planService;
        this.history = history;
    }

    /** Valeurs proposées dans le formulaire « Nouvelle ferme ». */
    @GetMapping("/config/default")
    public PlanRequest defaultConfig() {
        return PlanRequest.defaults();
    }

    /** Calcul pour la ferme indiquée (farmId obligatoire) : météo réelle, résultat enregistré dans l'historique. */
    @PostMapping("/plan")
    public PlanResponse plan(@RequestBody PlanRequest req, Authentication auth) {
        return planService.plan(req, auth.getName());
    }

    /** Historique d'une ferme de l'utilisateur, filtrable par période (?from=2026-11-01&to=2026-11-30). */
    @GetMapping("/farms/{farmId}/runs")
    public List<RunSummary> farmRuns(@PathVariable Long farmId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     Authentication auth) {
        return history.forFarm(auth.getName(), farmId, from, to);
    }

    /** Tous les calculs de l'utilisateur connecté (toutes ses fermes). */
    @GetMapping("/runs")
    public List<RunSummary> myRuns(Authentication auth) {
        return history.forUser(auth.getName());
    }

    /** Détail d'un ancien calcul (sans recalcul) ; 404 s'il appartient à un autre utilisateur. */
    @GetMapping("/runs/{id}")
    public RunDetail run(@PathVariable Long id, Authentication auth) {
        return history.detail(auth.getName(), id);
    }
}
