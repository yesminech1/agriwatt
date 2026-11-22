package tn.agriwatt.backend.dto;

public record ScheduleItem(
        String hour, boolean pumpOn, String source, double pumpKw,
        double waterPumpedM3, double waterDeliveredM3,
        double tankLevelM3, boolean outage,
        double solarKw, double tempC) {}   // solarKw / tempC : extension utilisée par le front (graphique solaire, température)