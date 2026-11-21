package tn.agriwatt.backend.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import tn.agriwatt.backend.dto.ForecastHour;

@Service
public class BaselinePlanner {

    /** HYPOTHÈSE : l'agriculteur pompe tous les jours de 6h à 10h (incluse). */
    public static final int START_HOUR = 6;
    public static final int END_HOUR = 10;

    public boolean[] plan(List<ForecastHour> hours) {
        boolean[] on = new boolean[hours.size()];
        for (int i = 0; i < on.length; i++) {
            int hod = LocalDateTime.parse(hours.get(i).hour()).getHour();
            on[i] = hod >= START_HOUR && hod <= END_HOUR;
        }
        return on;
    }
}