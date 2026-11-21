// controller/AnomalyController.java
package tn.agriwatt.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tn.agriwatt.backend.dto.AnomalyResponse;
import tn.agriwatt.backend.service.PythonClient;

@RestController
@RequestMapping("/api")
public class AnomalyController {

    private final PythonClient python;

    public AnomalyController(PythonClient python) {
        this.python = python;
    }

    @GetMapping("/anomalies")
    public AnomalyResponse anomalies(@RequestParam(defaultValue = "7") int days) {
        return python.anomalies(days);
    }
}