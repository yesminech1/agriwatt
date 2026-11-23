package tn.agriwatt.backend.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tn.agriwatt.backend.entity.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByEmail(String email);
    boolean existsByEmail(String email);
    java.util.List<AppUser> findBySmsAlertsEnabledTrue();
}
