package com.nekitvp.marathonbot.repository;

import com.nekitvp.marathonbot.model.GoalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GoalRepository extends JpaRepository<GoalEntity, Long> {

    @Query("""
            SELECT g
            FROM GoalEntity g
            WHERE g.userId = :userId
              AND g.marathonId = :marathonId
              AND g.archivedAt IS NULL
            ORDER BY g.position
            """)
    List<GoalEntity> findAllByUserIdAndMarathonIdOrderByPosition(Long userId, Long marathonId);

    boolean existsByUserIdAndMarathonIdAndPositionAndArchivedAtIsNull(
            Long userId,
            Long marathonId,
            Integer position
    );

    default boolean existsByUserIdAndMarathonIdAndPosition(Long userId, Long marathonId, Integer position) {
        return existsByUserIdAndMarathonIdAndPositionAndArchivedAtIsNull(userId, marathonId, position);
    }

    Optional<GoalEntity> findByIdAndArchivedAtIsNull(Long id);

    List<GoalEntity> findAllByMarathonIdAndUserIdAndArchivedAtIsNullOrderByPosition(Long marathonId, Long userId);

    @Query(value = """
            SELECT g.*
            FROM goal g
            WHERE g.marathon_id = :marathonId
              AND g.archived_at IS NULL
              AND g.user_id = (
                  SELECT u.telegram_id
                  FROM users u
                  JOIN goal gg ON gg.user_id = u.telegram_id
                  WHERE gg.marathon_id = :marathonId
                    AND gg.archived_at IS NULL
                  GROUP BY u.telegram_id
                  ORDER BY SUM(gg.asked_count) ASC
                  LIMIT 1
              )
              AND g.position <> 0
            ORDER BY g.asked_count ASC, RANDOM()
            LIMIT 1
            """, nativeQuery = true)
    GoalEntity findGoalForEveningQuestion(Long marathonId);
}
