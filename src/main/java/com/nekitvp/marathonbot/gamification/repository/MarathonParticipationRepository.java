package com.nekitvp.marathonbot.gamification.repository;

import com.nekitvp.marathonbot.gamification.model.MarathonParticipationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarathonParticipationRepository extends JpaRepository<MarathonParticipationEntity, Long> {

    Optional<MarathonParticipationEntity> findByMarathonIdAndUserIdAndLeftAtIsNull(
            Long marathonId,
            Long userId
    );

    @Query("""
            SELECT p
            FROM MarathonParticipationEntity p
            WHERE p.marathonId = :marathonId
              AND p.joinedAt <= :at
              AND (p.leftAt IS NULL OR p.leftAt > :at)
            ORDER BY p.userId
            """)
    List<MarathonParticipationEntity> findRosterAt(Long marathonId, LocalDateTime at);
}
