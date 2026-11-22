package tn.agriwatt.backend.controller;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tn.agriwatt.backend.dto.AuthDtos.LoginRequest;
import tn.agriwatt.backend.dto.AuthDtos.RegisterRequest;
import tn.agriwatt.backend.dto.AuthDtos.UserResponse;
import tn.agriwatt.backend.entity.AppUser;
import tn.agriwatt.backend.repository.AppUserRepository;
import tn.agriwatt.backend.service.PhoneNumbers;

/** Inscription / connexion (travail C). La déconnexion est gérée par Spring Security (POST /api/auth/logout). */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;

    public AuthController(AppUserRepository users, PasswordEncoder passwordEncoder,
                          AuthenticationManager authenticationManager, SecurityContextRepository contextRepository) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
    }

    /** Jeton CSRF demandé par login.html / register.html avant l'envoi du formulaire. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req,
                                      HttpServletRequest request, HttpServletResponse response) {
        String name = req.fullName() == null ? "" : req.fullName().trim();
        String email = normalize(req.email());
        String password = req.password() == null ? "" : req.password();
        if (name.length() < 2 || name.length() > 120 || !EMAIL.matcher(email).matches()
                || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Vérifiez le nom, l'adresse e-mail et le mot de passe (8 à 72 caractères)."));
        }
        String phone;
        try {
            phone = PhoneNumbers.normalize(req.phoneNumber());              // facultatif ; format +216XXXXXXXX
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("message", invalid.getMessage()));
        }
        boolean wantsSms = Boolean.TRUE.equals(req.smsAlerts());
        if (wantsSms && phone == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Indiquez un numéro de téléphone pour recevoir les alertes SMS."));
        }
        if (users.existsByEmail(email)) {
            return conflict();
        }
        try {
            AppUser created = new AppUser(name, email, passwordEncoder.encode(password));
            created.setPhoneNumber(phone);
            created.setSmsAlertsEnabled(wantsSms);
            users.saveAndFlush(created);
        } catch (DataIntegrityViolationException duplicate) {
            return conflict();
        }
        return authenticate(email, password, request, response);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req,
                                   HttpServletRequest request, HttpServletResponse response) {
        try {
            return authenticate(normalize(req.email()), req.password() == null ? "" : req.password(),
                    request, response);
        } catch (AuthenticationException failure) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Adresse e-mail ou mot de passe incorrect."));
        }
    }

    /** Utilisateur connecté (pratique pour afficher son nom dans l'interface). */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return users.findByEmail(authentication.getName())
                .map(u -> ResponseEntity.ok(new UserResponse(u.getId(), u.getFullName(), u.getEmail(), u.getPhoneNumber(), u.isSmsAlertsEnabled())))
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    private ResponseEntity<?> authenticate(String email, String password,
                                           HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        request.getSession(true);
        request.changeSessionId();                       // protection contre la fixation de session
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        AppUser user = users.findByEmail(email).orElseThrow();
        return ResponseEntity.ok(new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhoneNumber(), user.isSmsAlertsEnabled()));
    }

    private static ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "Un compte existe déjà pour cette adresse e-mail."));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
