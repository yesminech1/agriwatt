// dto/ForecastHour.java
package tn.agriwatt.backend.dto;

public record ForecastHour(
        String hour, double tempC, double irradianceWm2, double et0Mm,
        double pvKw, double outageRisk, double waterNeedM3) {}