package tn.agriwatt.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import tn.agriwatt.backend.entity.AnomalyEvent;

public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Long> {}