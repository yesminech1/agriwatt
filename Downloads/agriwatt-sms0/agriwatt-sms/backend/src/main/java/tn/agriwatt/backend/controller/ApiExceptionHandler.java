package tn.agriwatt.backend.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import tn.agriwatt.backend.service.FarmService.FarmNotFoundException;
import tn.agriwatt.backend.service.ServiceUnavailableException;

/** Erreurs des calculs, de l'historique et des anomalies, au format {"message": "..."}. */
@RestControllerAdvice(assignableTypes = {PlanController.class, AnomalyController.class})
public class ApiExceptionHandler {

    @ExceptionHandler(FarmNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(FarmNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<Map<String, String>> unavailable(ServiceUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", e.getMessage()));
    }
}
