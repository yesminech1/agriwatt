package tn.agriwatt.backend.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tn.agriwatt.backend.entity.PlanRun;

/** Toutes les lectures passent par le propriétaire de la ferme (farm.user.id). */
public interface PlanRunRepository extends JpaRepository<PlanRun, Long> {
    List<PlanRun> findByFarmUserIdOrderByCreatedAtDesc(Long userId);
    List<PlanRun> findByFarmIdAndFarmUserIdOrderByCreatedAtDesc(Long farmId, Long userId);
    List<PlanRun> findByFarmIdAndFarmUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long farmId, Long userId, LocalDateTime from, LocalDateTime to);
    Optional<PlanRun> findByIdAndFarmUserId(Long id, Long userId);
    List<PlanRun> findByFarmId(Long farmId);
}
