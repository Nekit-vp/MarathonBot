package com.nekitvp.marathonbot.gamification.repository;

import com.nekitvp.marathonbot.gamification.model.ExpeditionStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.util.Optional;

@Repository
public interface ExpeditionStateRepository extends JpaRepository<ExpeditionStateEntity, Long> {

    Optional<ExpeditionStateEntity> findByMarathonIdAndRuleVersion(Long marathonId, Integer ruleVersion);

    Optional<ExpeditionStateEntity> findFirstByMarathonIdOrderByRuleVersionDesc(Long marathonId);

    boolean existsByMarathonId(Long marathonId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s FROM ExpeditionStateEntity s
            WHERE s.marathonId = :marathonId AND s.ruleVersion = :ruleVersion
            """)
    Optional<ExpeditionStateEntity> findLockedByMarathonIdAndRuleVersion(Long marathonId, Integer ruleVersion);
}
