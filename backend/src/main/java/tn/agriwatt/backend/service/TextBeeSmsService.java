package tn.agriwatt.backend.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Fournisseur officiel d'envoi de SMS : TextBee (https://textbee.dev), via un téléphone Android passerelle.
 * La clé vient UNIQUEMENT de la variable d'environnement TEXTBEE_API_KEY (propriété textbee.api-key) :
 * elle n'est jamais écrite dans le code, jamais renvoyée au front et jamais journalisée.
 * Une réponse 200 signifie « mis en file d'attente », pas « livré » : on conserve smsBatchId et on suit le statut.
 */
@Service
public class TextBeeSmsService {

    private static final Logger log = LoggerFactory.getLogger(TextBeeSmsService.class);

    private final RestClient client;
    private final JsonMapper mapper;
    private final String apiKey;
    private final String deviceId;

    public TextBeeSmsService(@Value("${textbee.base-url:https://api.textbee.dev/api/v1}") String baseUrl,
                             @Value("${textbee.api-key:}") String apiKey,
                             @Value("${textbee.device-id:}") String deviceId,
                             JsonMapper mapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.deviceId = deviceId == null ? "" : deviceId.strip();
        this.mapper = mapper;
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    /** Résultat d'un envoi accepté par TextBee (mis en file d'attente, pas encore livré). */
    public record SendResult(String smsBatchId, int recipientCount, String warningCode, String providerMessage) {}

    /** Statut d'un message suivi via GET /gateway/messages?smsBatchId=… */
    public record MessageStatus(String id, String recipient, String status) {}

    /** État de la passerelle (test de connexion) : jamais la clé. */
    public record GatewayStatus(boolean configured, boolean reachable, int deviceCount, int enabledDevices,
                                String defaultDeviceName, String lastHeartbeat, String message) {}

    /** POST /gateway/send-sms */
    public SendResult send(String recipient, String message) {
        requireKey();
        Map<String, Object> body = deviceId.isEmpty()
                ? Map.of("recipients", List.of(recipient), "message", message)
                : Map.of("recipients", List.of(recipient), "message", message, "deviceId", deviceId);
        Map<String, Object> resp = call(() -> client.post().uri("/gateway/send-sms")
                .header("x-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class));
        Map<String, Object> data = asMap(resp == null ? null : resp.get("data"));
        String batchId = str(data.get("smsBatchId"));
        if (batchId == null) {
            throw new TextBeeException(TextBeeException.Kind.OTHER, 200,
                    "Réponse TextBee inattendue : aucun identifiant d'envoi (smsBatchId).");
        }
        Map<String, Object> warning = asMap(data.get("warning"));
        String warningCode = str(warning.get("code"));
        if (warningCode != null) log.warn("TextBee : envoi {} accepté avec avertissement {}", batchId, warningCode);
        Object count = data.get("recipientCount");
        return new SendResult(batchId, count instanceof Number n ? n.intValue() : 1, warningCode, str(data.get("message")));
    }

    /** GET /gateway/messages?smsBatchId=… : statut de chaque message du lot (pending, dispatched, sent, delivered, failed, unknown). */
    public List<MessageStatus> batchStatus(String smsBatchId) {
        requireKey();
        Map<String, Object> resp = call(() -> client.get()
                .uri(b -> b.path("/gateway/messages").queryParam("smsBatchId", smsBatchId).queryParam("limit", 100).build())
                .header("x-api-key", apiKey)
                .retrieve()
                .body(Map.class));
        List<MessageStatus> out = new ArrayList<>();
        Object data = resp == null ? null : resp.get("data");
        if (data instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> m = asMap(o);
                String status = str(m.get("status"));
                out.add(new MessageStatus(str(m.get("_id")), str(m.get("recipient")),
                        status == null ? "unknown" : status.toLowerCase(Locale.ROOT)));
            }
        }
        return out;
    }

    /** GET /gateway/devices : test de connexion (clé valide ? téléphone passerelle actif ?). */
    public GatewayStatus gatewayStatus() {
        if (!isConfigured()) {
            return new GatewayStatus(false, false, 0, 0, null, null,
                    "TextBee n'est pas configuré : définissez la variable d'environnement TEXTBEE_API_KEY puis redémarrez Spring Boot.");
        }
        try {
            Map<String, Object> resp = call(() -> client.get().uri("/gateway/devices")
                    .header("x-api-key", apiKey).retrieve().body(Map.class));
            Object data = resp == null ? null : resp.get("data");
            int count = 0, enabled = 0;
            String name = null, heartbeat = null;
            if (data instanceof List<?> list) {
                for (Object o : list) {
                    Map<String, Object> d = asMap(o);
                    count++;
                    boolean on = Boolean.TRUE.equals(d.get("enabled"));
                    if (on) enabled++;
                    boolean chosen = deviceId.isEmpty() ? Boolean.TRUE.equals(d.get("isDefault")) : deviceId.equals(str(d.get("_id")));
                    if (chosen || (name == null && on)) {
                        name = firstNonNull(str(d.get("name")), str(d.get("model")), str(d.get("brand")), "téléphone passerelle");
                        heartbeat = str(d.get("lastHeartbeat"));
                    }
                }
            }
            String msg = count == 0 ? "Clé valide, mais aucun téléphone n'est associé au compte TextBee."
                    : enabled == 0 ? "Clé valide, mais aucun téléphone passerelle n'est activé."
                    : "Connexion TextBee opérationnelle.";
            return new GatewayStatus(true, true, count, enabled, name, heartbeat, msg);
        } catch (TextBeeException e) {
            return new GatewayStatus(true, false, 0, 0, null, null, e.getMessage());
        }
    }

    // ---------- appels et classification des erreurs ----------

    private interface Call { Map<String, Object> run(); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(Call c) {
        try {
            return c.run();
        } catch (RestClientResponseException e) {
            TextBeeException err = classify(e.getStatusCode().value(), e.getResponseBodyAsString());
            log.warn("TextBee a répondu HTTP {} ({})", e.getStatusCode().value(), err.getKind());
            throw err;
        } catch (ResourceAccessException e) {
            log.warn("TextBee injoignable : {}", e.getClass().getSimpleName());
            throw new TextBeeException(TextBeeException.Kind.NETWORK, null,
                    "TextBee est injoignable (réseau, DNS ou délai dépassé). Nouvel essai automatique.");
        }
    }

    TextBeeException classify(int status, String body) {
        String detail = providerMessage(body);
        String low = detail.toLowerCase(Locale.ROOT);
        return switch (status) {
            case 401 -> new TextBeeException(TextBeeException.Kind.INVALID_API_KEY, 401,
                    "Clé API TextBee manquante, invalide ou révoquée : créez une nouvelle clé dans le tableau de bord TextBee et mettez à jour TEXTBEE_API_KEY.");
            case 403 -> new TextBeeException(TextBeeException.Kind.ACCOUNT_FORBIDDEN, 403,
                    "Le compte TextBee n'est pas autorisé à envoyer des SMS (abonnement ou compte suspendu).");
            case 404 -> new TextBeeException(TextBeeException.Kind.DEVICE_UNAVAILABLE, 404,
                    "Téléphone passerelle introuvable (TEXTBEE_DEVICE_ID incorrect).");
            case 429 -> new TextBeeException(TextBeeException.Kind.RATE_LIMITED, 429,
                    "Limite d'envoi TextBee atteinte (quota journalier, mensuel ou par lot). Nouvel essai plus tard.");
            case 400 -> {
                if (low.contains("device") || low.contains("push") || low.contains("sim")) {
                    yield new TextBeeException(TextBeeException.Kind.DEVICE_UNAVAILABLE, 400,
                            "Téléphone passerelle indisponible (non connecté, désactivé ou SIM absente) : " + detail);
                }
                if (low.contains("recipient") || low.contains("phone") || low.contains("number") || low.contains("letter")) {
                    yield new TextBeeException(TextBeeException.Kind.INVALID_NUMBER, 400, "Numéro de téléphone refusé par TextBee : " + detail);
                }
                if (low.contains("email")) {
                    yield new TextBeeException(TextBeeException.Kind.ACCOUNT_FORBIDDEN, 400, "Compte TextBee non vérifié : " + detail);
                }
                yield new TextBeeException(TextBeeException.Kind.INVALID_MESSAGE, 400, "Envoi refusé par TextBee : " + detail);
            }
            default -> status >= 500
                    ? new TextBeeException(TextBeeException.Kind.SERVER_ERROR, status, "TextBee est momentanément indisponible (HTTP " + status + ").")
                    : new TextBeeException(TextBeeException.Kind.OTHER, status, "Erreur TextBee HTTP " + status + " : " + detail);
        };
    }

    /** Champ « message » ou « error » de la réponse d'erreur (jamais d'en-têtes, donc jamais la clé). */
    private String providerMessage(String body) {
        if (body == null || body.isBlank()) return "sans détail";
        try {
            Map<?, ?> m = mapper.readValue(body, Map.class);
            Object v = m.get("message") != null ? m.get("message") : m.get("error");
            String s = v instanceof List<?> l ? String.join(" ; ", l.stream().map(String::valueOf).toList()) : String.valueOf(v);
            return s.length() > 200 ? s.substring(0, 200) : s;
        } catch (Exception e) {
            return body.length() > 200 ? body.substring(0, 200) : body;
        }
    }

    private void requireKey() {
        if (!isConfigured()) {
            throw new TextBeeException(TextBeeException.Kind.NOT_CONFIGURED, null,
                    "TextBee n'est pas configuré : variable d'environnement TEXTBEE_API_KEY absente.");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonNull(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
