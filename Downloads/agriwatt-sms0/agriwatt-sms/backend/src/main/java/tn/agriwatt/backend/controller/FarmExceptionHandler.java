package tn.agriwatt.backend.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import tn.agriwatt.backend.service.FarmService.FarmNotFoundException;
import tn.agriwatt.backend.service.FarmService.FarmValidationException;

/**
 * Erreurs de /api/farms au format {"message": "...", "errors": {"champ": "message"}}.
 * Limité à FarmController : les autres endpoints gardent leur comportement actuel.
 */
@RestControllerAdvice(assignableTypes = FarmController.class)
public class FarmExceptionHandler {

    @ExceptionHandler(FarmNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(FarmNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(err -> errors.putIfAbsent(err.getField(), err.getDefaultMessage()));
        return badRequest(errors);
    }

    @ExceptionHandler(FarmValidationException.class)
    public ResponseEntity<Map<String, Object>> inconsistent(FarmValidationException e) {
        return badRequest(e.getErrors());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("message", "Données de la ferme illisibles (JSON invalide)."));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(Map<String, String> errors) {
        return ResponseEntity.badRequest()
                .body(Map.of("message", "Paramètres de la ferme invalides.", "errors", errors));
    }
}
