package tn.agriwatt.backend.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tn.agriwatt.backend.entity.SmsAlert;

public interface SmsAlertRepository extends JpaRepository<SmsAlert, Long> {
    Optional<SmsAlert> findByFarmIdAndOutageStart(Long farmId, LocalDateTime outageStart);
    List<SmsAlert> findByFarmIdAndStatusAndOutageStartBetween(Long farmId, SmsAlert.Status status, LocalDateTime from, LocalDateTime to);
    List<SmsAlert> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(SmsAlert.Status status, LocalDateTime now);
    List<SmsAlert> findByStatusAndSmsBatchIdIsNotNullAndSentAtAfter(SmsAlert.Status status, LocalDateTime after);
    List<SmsAlert> findByUserIdAndStatus(Long userId, SmsAlert.Status status);
    List<SmsAlert> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);
    List<SmsAlert> findByUserIdAndKindAndCreatedAtAfter(Long userId, SmsAlert.Kind kind, LocalDateTime after);
    void deleteByFarmId(Long farmId);
}
