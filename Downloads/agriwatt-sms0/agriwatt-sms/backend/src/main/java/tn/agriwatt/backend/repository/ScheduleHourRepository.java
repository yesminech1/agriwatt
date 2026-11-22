package tn.agriwatt.backend.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tn.agriwatt.backend.entity.ScheduleHour;

public interface ScheduleHourRepository extends JpaRepository<ScheduleHour, Long> {
    List<ScheduleHour> findByRunIdOrderByIdAsc(Long runId);
    void deleteByRunIdIn(Collection<Long> runIds);
}
