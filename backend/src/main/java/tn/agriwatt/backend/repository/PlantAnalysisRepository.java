package tn.agriwatt.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tn.agriwatt.backend.entity.PlantAnalysis;

public interface PlantAnalysisRepository extends JpaRepository<PlantAnalysis, Long> {
    List<PlantAnalysis> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);
    Optional<PlantAnalysis> findByIdAndUserId(Long id, Long userId);
}
