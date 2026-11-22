package tn.agriwatt.backend.controller;

import java.util.Map;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/google")
public class GoogleOAuthStatusController {
    private final Environment environment;

    public GoogleOAuthStatusController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/status")
    public Map<String, Boolean> status() {
        boolean configured = StringUtils.hasText(environment.getProperty(
                "spring.security.oauth2.client.registration.google.client-id"))
                && StringUtils.hasText(environment.getProperty(
                "spring.security.oauth2.client.registration.google.client-secret"));
        return Map.of("configured", configured);
    }
}
