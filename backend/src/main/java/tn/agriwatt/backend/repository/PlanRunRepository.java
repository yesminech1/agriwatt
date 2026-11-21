package tn.agriwatt.backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import tn.agriwatt.backend.entity.PlanRun;

public interface PlanRunRepository extends JpaRepository<PlanRun, Long> {
    List<PlanRun> findAllByOrderByCreatedAtDesc();
}