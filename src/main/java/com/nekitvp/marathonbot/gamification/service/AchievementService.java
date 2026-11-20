package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.gamification.model.RewardKind;
import com.nekitvp.marathonbot.gamification.model.RewardNotificationMode;
import com.nekitvp.marathonbot.gamification.rule.OccurrenceKeyContext;
import com.nekitvp.marathonbot.gamification.rule.PersonalRewardEvaluator;
import com.nekitvp.marathonbot.gamification.rule.RewardCatalog;
import com.nekitvp.marathonbot.gamification.rule.RewardCode;
import com.nekitvp.marathonbot.gamification.rule.RewardDefinition;
import com.nekitvp.marathonbot.gamification.rule.RewardEvaluationContext;
import com.nekitvp.marathonbot.gamification.rule.StabilityAndFinishRewardEvaluator;
import com.nekitvp.marathonbot.gamification.rule.TeamRewardEvaluator;
import com.nekitvp.marathonbot.gamification.rule.UserDaySnapshot;
import com.nekitvp.marathonbot.model.GoalEntity;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.repository.GoalRepository;
import com.nekitvp.marathonbot.repository.HistoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Materializes deterministic reward contexts and persists their logical occurrences idempotently. */
@Service
public class AchievementService {

    public static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");

    private static final String INSERT_AWARD = """
            INSERT INTO reward_award(
                marathon_id, user_id, reward_code, reward_kind, occurrence_key, rule_version,
                goal_id_snapshot, goal_name_snapshot, awarded_at, earned_on, notification_mode, shown_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (marathon_id, user_id, reward_code, occurrence_key, rule_version) DO NOTHING
            """;

    private final HistoryRepository historyRepository;
    private final GoalRepository goalRepository;
    private final PersonalRewardEvaluator personalRewardEvaluator;
    private final StabilityAndFinishRewardEvaluator stabilityAndFinishRewardEvaluator;
    private final RewardCatalog rewardCatalog;
    private final JdbcTemplate jdbcTemplate;

    public AchievementService(
            HistoryRepository historyRepository,
            GoalRepository goalRepository,
            PersonalRewardEvaluator personalRewardEvaluator,
            StabilityAndFinishRewardEvaluator stabilityAndFinishRewardEvaluator,
            RewardCatalog rewardCatalog,
            JdbcTemplate jdbcTemplate
    ) {
        this.historyRepository = Objects.requireNonNull(historyRepository, "historyRepository");
        this.goalRepository = Objects.requireNonNull(goalRepository, "goalRepository");
        this.personalRewardEvaluator = Objects.requireNonNull(personalRewardEvaluator, "personalRewardEvaluator");
        this.stabilityAndFinishRewardEvaluator = Objects.requireNonNull(
                stabilityAndFinishRewardEvaluator, "stabilityAndFinishRewardEvaluator");
        this.rewardCatalog = Objects.requireNonNull(rewardCatalog, "rewardCatalog");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Transactional(readOnly = true)
    public RewardEvaluationContext buildContext(
            MarathonEntity marathon,
            Long userId,
            int rulesVersion,
            LocalDate evaluationDate,
            boolean marathonFinished
    ) {
        return buildContext(
                marathon, userId, rulesVersion, evaluationDate, marathonFinished,
                Boolean.TRUE.equals(marathon.getRestOnSaturday()),
                Boolean.TRUE.equals(marathon.getRestOnSunday())
        );
    }

    @Transactional(readOnly = true)
    public RewardEvaluationContext buildContext(
            MarathonEntity marathon,
            Long userId,
            int rulesVersion,
            LocalDate evaluationDate,
            boolean marathonFinished,
            boolean restOnSaturday,
            boolean restOnSunday
    ) {
        Objects.requireNonNull(marathon, "marathon");
        return buildContext(marathon, userId, rulesVersion, evaluationDate, marathonFinished,
                marathon.getDateStart().toLocalDate(), marathon.getDateEnd().toLocalDate(),
                restOnSaturday, restOnSunday);
    }

    @Transactional(readOnly = true)
    public RewardEvaluationContext buildContext(
            MarathonEntity marathon,
            Long userId,
            int rulesVersion,
            LocalDate evaluationDate,
            boolean marathonFinished,
            LocalDate windowStart,
            LocalDate windowEnd,
            boolean restOnSaturday,
            boolean restOnSunday
    ) {
        Objects.requireNonNull(marathon, "marathon");
        Objects.requireNonNull(marathon.getId(), "marathon.id");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        Objects.requireNonNull(evaluationDate, "evaluationDate");

        LocalDate marathonStart = windowStart;
        LocalDate marathonEnd = windowEnd;
        LocalDate lastDate = evaluationDate.isBefore(marathonEnd) ? evaluationDate : marathonEnd;
        if (lastDate.isBefore(marathonStart)) {
            return new RewardEvaluationContext(
                    marathon.getId(), userId, rulesVersion, evaluationDate, marathonStart, marathonEnd,
                    MOSCOW_ZONE, marathonFinished, List.of());
        }

        List<HistoryRepository.GamificationHistoryProjection> facts = historyRepository.findGamificationHistory(
                marathon.getId(), userId, marathonStart.atStartOfDay(), lastDate.plusDays(1).atStartOfDay());
        Map<LocalDate, List<HistoryRepository.GamificationHistoryProjection>> byDate = new LinkedHashMap<>();
        facts.forEach(fact -> byDate.computeIfAbsent(fact.getCreatedAt().toLocalDate(), ignored -> new ArrayList<>())
                .add(fact));
        List<GoalEntity> activeGoals = goalRepository
                .findAllByMarathonIdAndUserIdAndArchivedAtIsNullOrderByPosition(marathon.getId(), userId);

        List<UserDaySnapshot> days = new ArrayList<>();
        for (LocalDate date = marathonStart; !date.isAfter(lastDate); date = date.plusDays(1)) {
            if (isConfiguredRestDay(restOnSaturday, restOnSunday, date)) {
                days.add(UserDaySnapshot.configuredRest(date));
            } else {
                days.add(snapshot(date, byDate.getOrDefault(date, List.of()), date.equals(lastDate) ? activeGoals : List.of()));
            }
        }
        return new RewardEvaluationContext(
                marathon.getId(), userId, rulesVersion, evaluationDate, marathonStart, marathonEnd,
                MOSCOW_ZONE, marathonFinished, days);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NewRewards evaluateAndPersist(
            RewardEvaluationContext context,
            RewardNotificationMode notificationMode,
            LocalDateTime awardedAt
    ) {
        List<PersonalRewardEvaluator.RewardOccurrence> occurrences = new ArrayList<>(
                personalRewardEvaluator.evaluate(context));
        occurrences.addAll(stabilityAndFinishRewardEvaluator.evaluate(context));
        return persistOccurrences(context.marathonId(), context.userId(), occurrences, notificationMode, awardedAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NewRewards persistWeeklyEarlyBird(
            Long marathonId,
            Long userId,
            int rulesVersion,
            LocalDate weekEnd,
            RewardNotificationMode notificationMode,
            LocalDateTime awardedAt
    ) {
        RewardDefinition definition = rewardCatalog.definition(rulesVersion, RewardCode.EARLY_BIRD_WEEK);
        NewReward reward = new NewReward(
                userId,
                definition.code(),
                definition.kind(),
                definition.occurrenceKey(OccurrenceKeyContext.onDate(weekEnd)),
                weekEnd,
                null,
                null,
                notificationMode
        );
        return insert(marathonId, rulesVersion, reward, awardedAt)
                ? new NewRewards(List.of(reward))
                : NewRewards.empty();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public NewRewards persistTeamRewards(
            Long marathonId,
            int rulesVersion,
            List<TeamRewardEvaluator.TeamRewardOccurrence> occurrences,
            RewardNotificationMode notificationMode,
            Long immediateUserId,
            LocalDateTime awardedAt
    ) {
        List<NewReward> inserted = new ArrayList<>();
        for (TeamRewardEvaluator.TeamRewardOccurrence occurrence : occurrences) {
            RewardDefinition definition = rewardCatalog.definition(rulesVersion, occurrence.code());
            String key = definition.occurrenceKey(occurrenceContext(definition, occurrence.earnedOn()));
            for (Long recipientId : occurrence.recipientUserIds()) {
                RewardNotificationMode recipientMode = recipientMode(notificationMode, immediateUserId, recipientId);
                NewReward candidate = new NewReward(
                        recipientId, occurrence.code(), definition.kind(), key, occurrence.earnedOn(), null, null,
                        recipientMode);
                if (insert(marathonId, rulesVersion, candidate, awardedAt)) {
                    inserted.add(candidate);
                }
            }
        }
        return new NewRewards(inserted);
    }

    private NewRewards persistOccurrences(
            Long marathonId,
            Long userId,
            List<PersonalRewardEvaluator.RewardOccurrence> occurrences,
            RewardNotificationMode mode,
            LocalDateTime awardedAt
    ) {
        List<NewReward> inserted = new ArrayList<>();
        for (PersonalRewardEvaluator.RewardOccurrence occurrence : occurrences) {
            NewReward candidate = new NewReward(
                    userId,
                    occurrence.code(),
                    occurrence.kind(),
                    occurrence.occurrenceKey(),
                    occurrence.earnedOn(),
                    occurrence.goalIdSnapshot(),
                    occurrence.goalNameSnapshot(),
                    mode
            );
            if (insert(marathonId, occurrence.rulesVersion(), candidate, awardedAt)) {
                inserted.add(candidate);
            }
        }
        return new NewRewards(inserted);
    }

    private boolean insert(Long marathonId, int rulesVersion, NewReward reward, LocalDateTime awardedAt) {
        LocalDateTime shownAt = reward.notificationMode() == RewardNotificationMode.PENDING ? null : awardedAt;
        int rows = jdbcTemplate.update(
                INSERT_AWARD,
                marathonId,
                reward.userId(),
                reward.code().name(),
                reward.kind().name(),
                reward.occurrenceKey(),
                rulesVersion,
                reward.goalIdSnapshot(),
                reward.goalNameSnapshot(),
                Timestamp.valueOf(awardedAt),
                java.sql.Date.valueOf(reward.earnedOn()),
                reward.notificationMode().name(),
                shownAt == null ? null : Timestamp.valueOf(shownAt)
        );
        return rows == 1;
    }

    private static UserDaySnapshot snapshot(
            LocalDate date,
            List<HistoryRepository.GamificationHistoryProjection> facts,
            List<GoalEntity> activeFallback
    ) {
        Map<Long, HistoryRepository.GamificationHistoryProjection> latestByGoal = new LinkedHashMap<>();
        facts.forEach(fact -> latestByGoal.put(fact.getGoalId(), fact));
        List<HistoryRepository.GamificationHistoryProjection> ordered = latestByGoal.values().stream()
                .sorted(Comparator.comparing(HistoryRepository.GamificationHistoryProjection::getGoalPosition)
                        .thenComparing(HistoryRepository.GamificationHistoryProjection::getGoalId))
                .toList();
        if (ordered.size() == 1
                && ordered.getFirst().getGoalPosition() == 0
                && Boolean.TRUE.equals(ordered.getFirst().getDone())
                && Boolean.TRUE.equals(ordered.getFirst().getDayOff())) {
            return UserDaySnapshot.personalDayOff(date);
        }

        HistoryRepository.GamificationHistoryProjection report = ordered.stream()
                .filter(fact -> fact.getGoalPosition() == 0)
                .findFirst()
                .orElse(null);
        boolean reportSubmitted = report != null && (Boolean.TRUE.equals(report.getReportSubmitted())
                || Boolean.TRUE.equals(report.getDone()));
        UserDaySnapshot.ReportGoalSnapshot reportSnapshot = report == null
                ? UserDaySnapshot.ReportGoalSnapshot.absent()
                : new UserDaySnapshot.ReportGoalSnapshot(
                        report.getDone(),
                        Boolean.TRUE.equals(report.getDone()) && reportSubmitted,
                        reportSubmitted,
                        reportSubmitted ? report.getCreatedAt().toLocalTime() : null
                );
        List<UserDaySnapshot.PersonalGoalSnapshot> personalGoals = ordered.stream()
                .filter(fact -> fact.getGoalPosition() != 0)
                .map(fact -> new UserDaySnapshot.PersonalGoalSnapshot(
                        fact.getGoalId(), fact.getGoalName(), fact.getDone()))
                .toList();
        if (personalGoals.isEmpty() && facts.isEmpty()) {
            personalGoals = activeFallback.stream()
                    .filter(goal -> goal.getPosition() != 0)
                    .map(goal -> new UserDaySnapshot.PersonalGoalSnapshot(goal.getId(), goal.getName(), null))
                    .toList();
        }
        return new UserDaySnapshot(date, false, false, reportSnapshot, personalGoals);
    }

    /**
     * Pure day-level summary for the group report tail: how long the green run is and which report this is.
     * A configured rest day or a personal day off is neutral and does not break the run.
     */
    public static DayStats dayStats(RewardEvaluationContext context) {
        Objects.requireNonNull(context, "context");
        int greenStreak = 0;
        int reportNumber = 0;
        boolean greenOnLastDay = false;
        LocalDate lastActiveDate = null;
        for (UserDaySnapshot day : context.days()) {
            if (day.configuredRestDay() || day.personalDayOff()) {
                continue;
            }
            boolean submitted = day.reportSubmitted();
            if (submitted) {
                reportNumber++;
            }
            boolean green = submitted
                    && !day.personalGoals().isEmpty()
                    && day.personalGoals().stream().allMatch(goal -> Boolean.TRUE.equals(goal.done()));
            greenStreak = green ? greenStreak + 1 : 0;
            greenOnLastDay = green;
            lastActiveDate = day.activityDate();
        }
        boolean greenToday = greenOnLastDay && context.evaluationDate().equals(lastActiveDate);
        return new DayStats(greenToday, greenStreak, reportNumber);
    }

    public record DayStats(boolean greenToday, int greenStreak, int reportNumber) {
        public static DayStats empty() {
            return new DayStats(false, 0, 0);
        }
    }

    private static boolean isConfiguredRestDay(boolean restOnSaturday, boolean restOnSunday, LocalDate date) {
        return restOnSaturday && date.getDayOfWeek() == DayOfWeek.SATURDAY
                || restOnSunday && date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static OccurrenceKeyContext occurrenceContext(RewardDefinition definition, LocalDate earnedOn) {
        return switch (definition.occurrenceKeyStrategy()) {
            case DATE, ISO_WEEK -> OccurrenceKeyContext.onDate(earnedOn);
            case GOAL -> throw new IllegalArgumentException("Team reward cannot use a goal occurrence key");
            case TIER, FINISH, TEAM_CHECKPOINT -> OccurrenceKeyContext.empty();
        };
    }

    private static RewardNotificationMode recipientMode(
            RewardNotificationMode requested,
            Long immediateUserId,
            Long recipientId
    ) {
        if (requested == RewardNotificationMode.BACKFILL) {
            return RewardNotificationMode.BACKFILL;
        }
        if (requested == RewardNotificationMode.IMMEDIATE && Objects.equals(immediateUserId, recipientId)) {
            return RewardNotificationMode.IMMEDIATE;
        }
        return RewardNotificationMode.PENDING;
    }

    public record NewRewards(List<NewReward> rewards) {
        public NewRewards {
            rewards = List.copyOf(Objects.requireNonNull(rewards, "rewards"));
        }

        public static NewRewards empty() {
            return new NewRewards(List.of());
        }

        public NewRewards plus(NewRewards other) {
            List<NewReward> combined = new ArrayList<>(rewards);
            combined.addAll(other.rewards);
            return new NewRewards(combined);
        }

        public NewRewards forUser(Long userId) {
            return new NewRewards(rewards.stream().filter(reward -> reward.userId().equals(userId)).toList());
        }
    }

    public record NewReward(
            Long userId,
            RewardCode code,
            RewardKind kind,
            String occurrenceKey,
            LocalDate earnedOn,
            Long goalIdSnapshot,
            String goalNameSnapshot,
            RewardNotificationMode notificationMode
    ) {
        public NewReward {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(occurrenceKey, "occurrenceKey");
            Objects.requireNonNull(earnedOn, "earnedOn");
            Objects.requireNonNull(notificationMode, "notificationMode");
        }
    }
}
