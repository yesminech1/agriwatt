// dto/ForecastResponse.java
package tn.agriwatt.backend.dto;

import java.util.List;

/** source : openmeteo | cache (prévisions réelles du jour en cache côté Python). */
public record ForecastResponse(List<ForecastHour> hours, String source, Double lat, Double lon) {}