package tn.agriwatt.backend.service;

/** Erreur TextBee classée. Le message est sûr à afficher : il ne contient jamais la clé API. */
public class TextBeeException extends RuntimeException {

    public enum Kind {
        NOT_CONFIGURED(false),       // TEXTBEE_API_KEY absente
        INVALID_API_KEY(false),      // 401
        ACCOUNT_FORBIDDEN(false),    // 403
        DEVICE_UNAVAILABLE(true),    // 400 : aucun appareil actif / appareil désactivé / envoi vers le téléphone impossible
        INVALID_NUMBER(false),       // 400 : destinataire invalide
        INVALID_MESSAGE(false),      // 400 : message trop long, etc.
        RATE_LIMITED(true),          // 429 : quota atteint
        NETWORK(true),               // délai, DNS, connexion refusée
        SERVER_ERROR(true),          // 5xx
        OTHER(false);

        public final boolean retryable;
        Kind(boolean retryable) { this.retryable = retryable; }
    }

    private final Kind kind;
    private final Integer httpStatus;

    public TextBeeException(Kind kind, Integer httpStatus, String message) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public Kind getKind() { return kind; }
    public Integer getHttpStatus() { return httpStatus; }

    /** Erreur de configuration du compte : inutile de compter une tentative par alerte, on réessaiera plus tard. */
    public boolean isConfigurationProblem() {
        return kind == Kind.NOT_CONFIGURED || kind == Kind.INVALID_API_KEY || kind == Kind.ACCOUNT_FORBIDDEN;
    }
}
