package com.nekitvp.marathonbot.repository;

import com.nekitvp.marathonbot.model.HistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface HistoryRepository extends JpaRepository<HistoryEntity, Long> {

    interface GamificationHistoryProjection {
        Long getHistoryId();

        LocalDateTime getCreatedAt();

        Boolean getDone();

        Boolean getReportSubmitted();

        Boolean getDayOff();

        Long getGoalId();

        String getGoalName();

        Integer getGoalPosition();
    }

    @Query("""
            SELECT h.id AS historyId,
                   h.createdAt AS createdAt,
                   h.done AS done,
                   h.reportSubmitted AS reportSubmitted,
                   h.dayOff AS dayOff,
                   g.id AS goalId,
                   g.name AS goalName,
                   g.position AS goalPosition
            FROM HistoryEntity h
            JOIN h.goal g
            WHERE g.marathon.id = :marathonId
              AND g.userId = :userId
              AND h.createdAt >= :fromInclusive
              AND h.createdAt < :toExclusive
            ORDER BY h.createdAt, g.position, h.id
            """)
    List<GamificationHistoryProjection> findGamificationHistory(
            Long marathonId,
            Long userId,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive
    );

    @Query("SELECT COUNT(h) > 0 FROM HistoryEntity h WHERE h.goal.userId = :telegramId AND h.createdAt >= :startOfDay AND h.createdAt <= :endOfDay")
    boolean existsByTelegramIdAndCreatedAtBetween(@Param("telegramId") Long telegramId,
                                                  @Param("startOfDay") LocalDateTime startOfDay,
                                                  @Param("endOfDay") LocalDateTime endOfDay);

    @Query("SELECT h FROM HistoryEntity h WHERE h.goal.userId = :telegramId AND h.createdAt >= :startOfDay AND h.createdAt <= :endOfDay")
    List<HistoryEntity> findByTelegramIdAndCreatedAtBetween(Long telegramId, LocalDateTime startOfDay, LocalDateTime endOfDay);

    List<HistoryEntity> findAllByCreatedAtBetweenAndDoneIsNull(LocalDateTime from, LocalDateTime to);

    List<HistoryEntity> findByGoalUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long userId, LocalDateTime from, LocalDateTime to);

    void deleteByGoalId(Long goalId);
}
