package com.nekitvp.marathonbot.gamification.repository;

import com.nekitvp.marathonbot.gamification.model.RewardAwardEntity;
import com.nekitvp.marathonbot.gamification.model.RewardKind;
import com.nekitvp.marathonbot.gamification.model.RewardNotificationMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface RewardAwardRepository extends JpaRepository<RewardAwardEntity, Long> {

    interface MedalCountProjection {
        String getRewardCode();

        long getAwardCount();

        LocalDateTime getLatestAwardedAt();
    }

    interface PendingAwardProjection {
        Long getId();

        String getRewardCode();

        RewardKind getRewardKind();

        Long getGoalIdSnapshot();

        String getGoalNameSnapshot();

        LocalDateTime getAwardedAt();
    }

    interface CabinetAwardProjection {
        String getRewardCode();

        Long getGoalIdSnapshot();

        String getGoalNameSnapshot();

        LocalDateTime getAwardedAt();
    }

    @Query("""
            SELECT a.rewardCode AS rewardCode,
                   COUNT(a.id) AS awardCount,
                   MAX(a.awardedAt) AS latestAwardedAt
            FROM RewardAwardEntity a
            WHERE a.marathonId = :marathonId
              AND a.userId = :userId
              AND a.rewardKind = :rewardKind
            GROUP BY a.rewardCode
            ORDER BY MAX(a.awardedAt) DESC, a.rewardCode ASC
            """)
    List<MedalCountProjection> findRewardCounts(
            Long marathonId,
            Long userId,
            RewardKind rewardKind
    );

    default List<MedalCountProjection> findMedalCounts(Long marathonId, Long userId) {
        return findRewardCounts(marathonId, userId, RewardKind.MEDAL);
    }

    @Query("""
            SELECT a.id AS id,
                   a.rewardCode AS rewardCode,
                   a.rewardKind AS rewardKind,
                   a.goalIdSnapshot AS goalIdSnapshot,
                   a.goalNameSnapshot AS goalNameSnapshot,
                   a.awardedAt AS awardedAt
            FROM RewardAwardEntity a
            WHERE a.marathonId = :marathonId
              AND a.userId = :userId
              AND a.notificationMode = :notificationMode
              AND a.shownAt IS NULL
            ORDER BY a.awardedAt, a.id
            """)
    List<PendingAwardProjection> findUnshownByNotificationMode(
            Long marathonId,
            Long userId,
            RewardNotificationMode notificationMode
    );

    default List<PendingAwardProjection> findPendingAwards(Long marathonId, Long userId) {
        return findUnshownByNotificationMode(marathonId, userId, RewardNotificationMode.PENDING);
    }

    List<RewardAwardEntity> findAllByMarathonIdAndUserIdOrderByAwardedAtDescIdDesc(
            Long marathonId,
            Long userId
    );

    List<RewardAwardEntity> findAllByMarathonIdAndAwardedAtBetweenOrderByAwardedAtAscIdAsc(
            Long marathonId,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive
    );

    List<RewardAwardEntity> findAllByMarathonIdAndRuleVersionAndEarnedOnBetweenAndNotificationModeNotOrderByEarnedOnAscIdAsc(
            Long marathonId,
            Integer ruleVersion,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            RewardNotificationMode excludedMode
    );

    List<RewardAwardEntity> findAllByMarathonIdAndRuleVersionAndRewardCodeIn(
            Long marathonId,
            Integer ruleVersion,
            List<String> rewardCodes
    );

    @Query("""
            SELECT a.rewardCode AS rewardCode,
                   a.goalIdSnapshot AS goalIdSnapshot,
                   a.goalNameSnapshot AS goalNameSnapshot,
                   a.awardedAt AS awardedAt
            FROM RewardAwardEntity a
            WHERE a.marathonId = :marathonId AND a.userId = :userId
            ORDER BY a.awardedAt DESC, a.id DESC
            """)
    List<CabinetAwardProjection> findCabinetAwards(Long marathonId, Long userId);
}
