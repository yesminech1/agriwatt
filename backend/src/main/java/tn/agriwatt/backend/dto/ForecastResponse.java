// dto/ForecastResponse.java
package tn.agriwatt.backend.dto;

import java.util.List;

public record ForecastResponse(List<ForecastHour> hours) {}