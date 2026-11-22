package tn.agriwatt.backend.service;

/** Service externe indisponible (prévisions météo réelles, analyse d'anomalies) : renvoyé en HTTP 503 avec un message clair. */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) { super(message); }
}
