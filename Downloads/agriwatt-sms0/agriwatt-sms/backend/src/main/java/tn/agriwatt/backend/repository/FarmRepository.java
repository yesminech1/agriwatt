package tn.agriwatt.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tn.agriwatt.backend.entity.Farm;

/** Toutes les recherches filtrent par propriétaire (user.id) : une ferme d'un autre utilisateur est introuvable. */
public interface FarmRepository extends JpaRepository<Farm, Long> {
    List<Farm> findAllByUserIdOrderByCreatedAtAsc(Long userId);
    Optional<Farm> findByIdAndUserId(Long id, Long userId);
}
