package tn.agriwatt.backend.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tn.agriwatt.backend.dto.FarmDtos.FarmRequest;
import tn.agriwatt.backend.dto.FarmDtos.FarmResponse;
import tn.agriwatt.backend.service.FarmService;

/**
 * Fermes de l'utilisateur connecté. L'identité vient de Spring Security (authentication.getName() = e-mail,
 * connexion par formulaire comme par Google). /api/** exige déjà une session (SecurityConfig).
 */
@RestController
@RequestMapping("/api/farms")
public class FarmController {

    private final FarmService farms;

    public FarmController(FarmService farms) {
        this.farms = farms;
    }

    @GetMapping
    public List<FarmResponse> list(Authentication auth) {
        return farms.list(auth.getName());
    }

    @GetMapping("/{id}")
    public FarmResponse get(@PathVariable Long id, Authentication auth) {
        return farms.get(auth.getName(), id);
    }

    @PostMapping
    public ResponseEntity<FarmResponse> create(@Valid @RequestBody FarmRequest req, Authentication auth) {
        FarmResponse created = farms.create(auth.getName(), req);
        return ResponseEntity.created(URI.create("/api/farms/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public FarmResponse update(@PathVariable Long id, @Valid @RequestBody FarmRequest req, Authentication auth) {
        return farms.update(auth.getName(), id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication auth) {
        farms.delete(auth.getName(), id);
        return ResponseEntity.noContent().build();
    }
}
