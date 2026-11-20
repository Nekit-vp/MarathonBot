package com.nekitvp.marathonbot.gamification.repository;

import com.nekitvp.marathonbot.gamification.model.GamificationNotificationEntity;
import com.nekitvp.marathonbot.gamification.model.GamificationNotificationStatus;
import com.nekitvp.marathonbot.gamification.model.GamificationNotificationType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface GamificationNotificationRepository extends JpaRepository<GamificationNotificationEntity, Long> {

    Optional<GamificationNotificationEntity> findByMarathonIdAndNotificationTypeAndOccurrenceKey(
            Long marathonId,
            GamificationNotificationType notificationType,
            String occurrenceKey
    );

    @Query("""
            SELECT n
            FROM GamificationNotificationEntity n
            WHERE n.status = :status
              AND (n.nextAttemptAt IS NULL OR n.nextAttemptAt <= :now)
            ORDER BY n.id
            """)
    List<GamificationNotificationEntity> findReadyForDispatch(
            GamificationNotificationStatus status,
            LocalDateTime now,
            Pageable pageable
    );
}
