package tn.agriwatt.backend.config;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import tn.agriwatt.backend.entity.AppUser;
import tn.agriwatt.backend.repository.AppUserRepository;

/** Valide l’adresse Google, crée ou retrouve le compte local puis conserve l’e-mail comme identifiant de session. */
@Service
public class GoogleOAuthUserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    private final OidcUserService oidcDelegate = new OidcUserService();
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    public GoogleOAuthUserService(AppUserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        if (!"google".equals(request.getClientRegistration().getRegistrationId())) {
            throw new OAuth2AuthenticationException(new OAuth2Error("unsupported_provider"));
        }

        OAuth2User profile = delegate.loadUser(request);
        String email = provisionAccount(profile.getAttribute("email"),
                profile.getAttribute("email_verified"), profile.getAttribute("name"));

        Map<String, Object> attributes = new HashMap<>(profile.getAttributes());
        attributes.put("email", email);
        return new DefaultOAuth2User(
                java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, "email");
    }

    public OidcUser loadOidcUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        if (!"google".equals(request.getClientRegistration().getRegistrationId())) {
            throw new OAuth2AuthenticationException(new OAuth2Error("unsupported_provider"));
        }
        OidcUser profile = oidcDelegate.loadUser(request);
        String email = provisionAccount(profile.getEmail(), profile.getEmailVerified(), profile.getFullName());
        return new DefaultOidcUser(profile.getAuthorities(), profile.getIdToken(), "email") {
            @Override
            public String getName() {
                return email;
            }
        };
    }

    private String provisionAccount(String rawEmail, Boolean verified, String rawName) {
        if (rawEmail == null || !Boolean.TRUE.equals(verified)) {
            throw new OAuth2AuthenticationException(new OAuth2Error("unverified_email"));
        }
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        String fullName = rawName;
        if (fullName == null || fullName.isBlank()) fullName = email.substring(0, email.indexOf('@'));
        fullName = fullName.trim();
        if (fullName.length() > 120) fullName = fullName.substring(0, 120);
        if (users.findByEmail(email).isEmpty()) {
            byte[] randomSecret = new byte[48];
            RANDOM.nextBytes(randomSecret);
            String encodedSecret = passwordEncoder.encode(Base64.getUrlEncoder().withoutPadding().encodeToString(randomSecret));
            try {
                users.saveAndFlush(new AppUser(fullName, email, encodedSecret));
            } catch (DataIntegrityViolationException concurrentLogin) {
                if (users.findByEmail(email).isEmpty()) throw concurrentLogin;
            }
        }
        return email;
    }
}
