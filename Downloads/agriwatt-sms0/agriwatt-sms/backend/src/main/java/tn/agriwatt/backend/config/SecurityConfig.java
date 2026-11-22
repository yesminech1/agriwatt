package tn.agriwatt.backend.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import tn.agriwatt.backend.repository.AppUserRepository;

/**
 * Sécurité (travail C, adapté au back-end de B) :
 * - comptes stockés en base (table user), mots de passe hachés BCrypt ;
 * - session HTTP + protection CSRF par cookie XSRF-TOKEN (lu par le JavaScript du front) ;
 * - pages et /api/** réservées aux utilisateurs connectés, sauf login / inscription.
 */
@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Spring Boot construit automatiquement le fournisseur d'authentification à partir de ce bean. */
    @Bean
    UserDetailsService userDetailsService(AppUserRepository users) {
        return email -> users.findByEmail(email)
                .map(u -> User.withUsername(u.getEmail()).password(u.getPasswordHash()).roles("USER").build())
                .orElseThrow(() -> new UsernameNotFoundException("Compte introuvable"));
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contextRepository,
                                            org.springframework.beans.factory.ObjectProvider<ClientRegistrationRepository> registrations,
                                            GoogleOAuthUserService googleUserService)
            throws Exception {
        http
            .securityContext(context -> context.securityContextRepository(contextRepository))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            // dépose le cookie XSRF-TOKEN à chaque réponse : le front peut toujours faire ses POST
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/login.html", "/register.html", "/css/auth.css", "/js/auth.js",
                            "/img/**", "/api/auth/**", "/oauth2/**", "/login/oauth2/**", "/error", "/favicon.ico").permitAll()
                    .anyRequest().authenticated())
            .exceptionHandling(errors -> errors
                    .authenticationEntryPoint((request, response, exception) -> {
                        if (request.getRequestURI().startsWith("/api/")) {
                            response.sendError(HttpStatus.UNAUTHORIZED.value());
                        } else {
                            response.sendRedirect("/login.html");
                        }
                    }))
            .logout(logout -> logout
                    .logoutUrl("/api/auth/logout")
                    .logoutSuccessHandler((request, response, authentication) ->
                            response.setStatus(HttpStatus.NO_CONTENT.value()))
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("JSESSIONID", "XSRF-TOKEN"));
        // Le démarrage local reste possible sans secrets OAuth; le flux apparaît dès que les
        // variables Google sont configurées dans l'environnement.
        if (registrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .loginPage("/login.html")
                    .defaultSuccessUrl("/index.html", true)
                    .failureUrl("/login.html?googleError")
                    .userInfoEndpoint(userInfo -> userInfo.userService(googleUserService)
                            .oidcUserService(googleUserService::loadOidcUser)));
        }
        return http.build();
    }

    /** Force la génération du jeton CSRF (chargé à la demande depuis Spring Security 6). */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
