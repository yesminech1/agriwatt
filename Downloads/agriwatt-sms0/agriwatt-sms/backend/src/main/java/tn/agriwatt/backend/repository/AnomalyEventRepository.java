package tn.agriwatt.backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tn.agriwatt.backend.entity.AnomalyEvent;

public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Long> {
    List<AnomalyEvent> findByFarmIdOrderByTimestampAsc(Long farmId);
    void deleteByFarmId(Long farmId);
}
