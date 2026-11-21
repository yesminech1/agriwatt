package tn.agriwatt.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tn.agriwatt.backend.dto.ForecastResponse;
import tn.agriwatt.backend.dto.PlanRequest;
import tn.agriwatt.backend.service.PythonClient;

@RestController
@RequestMapping("/api/debug")
public class DebugController {

    private final PythonClient python;

    public DebugController(PythonClient python) {
        this.python = python;
    }

    @GetMapping("/forecast")
    public ForecastResponse forecast() {
        return python.forecast(PlanRequest.defaults());
    }
}