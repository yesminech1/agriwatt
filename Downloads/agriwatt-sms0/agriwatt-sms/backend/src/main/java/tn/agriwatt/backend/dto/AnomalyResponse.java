// dto/AnomalyResponse.java
package tn.agriwatt.backend.dto;

import java.util.List;

public record AnomalyResponse(List<AnomalyPoint> points) {}