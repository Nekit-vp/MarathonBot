package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.gamification.model.RewardNotificationMode;
import com.nekitvp.marathonbot.gamification.rule.RewardCatalog;
import com.nekitvp.marathonbot.gamification.rule.RewardCode;
import com.nekitvp.marathonbot.gamification.rule.RewardDefinition;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Interactive, failure-isolated bridge between a committed report and its private reward block. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportGamificationService {

    private static final String CONSUME_PENDING = """
            WITH claimed AS (
                SELECT id
                FROM reward_award
                WHERE marathon_id = ?
                  AND user_id = ?
                  AND notification_mode = 'PENDING'
                  AND shown_at IS NULL
                ORDER BY awarded_at, id
                FOR UPDATE SKIP LOCKED
            )
            UPDATE reward_award award
            SET shown_at = ?
            FROM claimed
            WHERE award.id = claimed.id
            RETURNING award.reward_code, award.occurrence_key, award.rule_version
            """;

    private final GamificationService gamificationService;
    private final MarathonRepository marathonRepository;
    private final RewardCatalog rewardCatalog;
    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final AchievementService achievementService;
    private final GamificationMessageComposer messageComposer;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReportRewards recalculateForReport(
            Long marathonId,
            Long userId,
            LocalDate activityDate,
            LocalDateTime evaluatedAt
    ) {
        Objects.requireNonNull(marathonId, "marathonId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(activityDate, "activityDate");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");

        var marathon = marathonRepository.findById(marathonId).orElse(null);
        if (marathon == null || !Boolean.TRUE.equals(marathon.getGamificationEnabled())) {
            return ReportRewards.empty();
        }
        int rulesVersion = marathon.getGamificationRuleVersion() == null
                ? rewardCatalog.rulesVersion() : marathon.getGamificationRuleVersion();

        try {
            gamificationService.recalculate(new GamificationService.RecalculationRequest(
                    marathonId,
                    userId,
                    rulesVersion,
                    activityDate,
                    evaluatedAt,
                    RewardNotificationMode.PENDING,
                    false,
                    false
            ));
        } catch (RuntimeException exception) {
            log.error(
                    "Immediate gamification unavailable; saved report will be repaired by reconciliation: marathon={}, user={}, date={}",
                    marathonId, userId, activityDate, exception);
            eventPublisher.publishEvent(HistoryMutationRecalculationEvent.history(
                    marathonId, userId, activityDate, false));
        }

        Map<String, VisibleReward> visible = new LinkedHashMap<>();
        jdbcTemplate.query(
                CONSUME_PENDING,
                (resultSet, rowNumber) -> {
                    RewardCode code = RewardCode.valueOf(resultSet.getString("reward_code"));
                    String occurrenceKey = resultSet.getString("occurrence_key");
                    int awardRulesVersion = resultSet.getInt("rule_version");
                    return visible(code, occurrenceKey, rewardCatalog.definition(awardRulesVersion, code));
                },
                marathonId,
                userId,
                Timestamp.valueOf(evaluatedAt)
        ).forEach(reward -> visible.put(reward.key(), reward));

        return new ReportRewards(new ArrayList<>(visible.values()), groupTail(marathon, userId, rulesVersion, activityDate));
    }

    /** Never blocks the report: an unavailable tail degrades to the plain group message. */
    private String groupTail(
            com.nekitvp.marathonbot.model.MarathonEntity marathon,
            Long userId,
            int rulesVersion,
            LocalDate activityDate
    ) {
        try {
            AchievementService.DayStats stats = AchievementService.dayStats(
                    achievementService.buildContext(marathon, userId, rulesVersion, activityDate, false));
            return messageComposer.composeReportTail(
                    stats.greenToday(), stats.greenStreak(), stats.reportNumber());
        } catch (RuntimeException exception) {
            log.warn("Report tail unavailable: marathon={}, user={}, date={}",
                    marathon.getId(), userId, activityDate, exception);
            return "";
        }
    }

    private static VisibleReward visible(
            RewardCode code,
            String occurrenceKey,
            RewardDefinition definition
    ) {
        return new VisibleReward(definition.emoji(), definition.title(), code.name() + ':' + occurrenceKey);
    }

    public record ReportRewards(List<VisibleReward> rewards, String groupTail) {
        public ReportRewards {
            rewards = List.copyOf(Objects.requireNonNull(rewards, "rewards"));
            groupTail = Objects.requireNonNullElse(groupTail, "");
        }

        public ReportRewards(List<VisibleReward> rewards) {
            this(rewards, "");
        }

        public static ReportRewards empty() {
            return new ReportRewards(List.of(), "");
        }

        public String appendTo(String confirmation) {
            if (rewards.isEmpty()) {
                return confirmation;
            }
            StringBuilder result = new StringBuilder(confirmation.stripTrailing());
            result.append("\n\n🏅 Новые награды:\n");
            Map<String, Long> counts = new LinkedHashMap<>();
            rewards.forEach(reward -> counts.merge(reward.emoji() + ' ' + reward.title(), 1L, Long::sum));
            counts.forEach((label, count) -> {
                result.append(label);
                if (count > 1) {
                    result.append(" ×").append(count);
                }
                result.append('\n');
            });
            return result.toString().stripTrailing();
        }
    }

    public record VisibleReward(String emoji, String title, String key) {
        public VisibleReward(String emoji, String title) {
            this(emoji, title, emoji + ':' + title);
        }

        public VisibleReward {
            Objects.requireNonNull(emoji, "emoji");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(key, "key");
        }
    }
}
