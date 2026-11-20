package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.enumBot.MarathonStatus;
import com.nekitvp.marathonbot.gamification.model.ExpeditionStateEntity;
import com.nekitvp.marathonbot.gamification.repository.ExpeditionContributionRepository;
import com.nekitvp.marathonbot.gamification.repository.ExpeditionStateRepository;
import com.nekitvp.marathonbot.gamification.repository.RewardAwardRepository;
import com.nekitvp.marathonbot.gamification.rule.RewardCatalog;
import com.nekitvp.marathonbot.gamification.rule.RewardCode;
import com.nekitvp.marathonbot.gamification.rule.RewardDefinition;
import com.nekitvp.marathonbot.gamification.rule.RewardEvaluationContext;
import com.nekitvp.marathonbot.gamification.rule.UserDaySnapshot;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView.AchievementItem;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView.ExpeditionView;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView.GoalStreak;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView.MedalItem;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView.ProgressItem;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView.RewardItem;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView.StreakView;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds bounded cabinet DTOs and owns the cabinet side of pending-award claiming. */
@Service
public class GamificationViewService {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final String CLAIM_PENDING = """
            WITH claimed AS (
                SELECT id
                FROM reward_award
                WHERE marathon_id = ?
                  AND user_id = ?
                  AND notification_mode = 'PENDING'
                  AND shown_at IS NULL
                ORDER BY awarded_at, id
                LIMIT 8
                FOR UPDATE SKIP LOCKED
            )
            UPDATE reward_award award
            SET shown_at = ?
            FROM claimed
            WHERE award.id = claimed.id
            RETURNING award.reward_code, award.rule_version, award.goal_name_snapshot
            """;

    private final RewardAwardRepository awardRepository;
    private final ExpeditionStateRepository stateRepository;
    private final ExpeditionContributionRepository contributionRepository;
    private final RewardCatalog rewardCatalog;
    private final AchievementService achievementService;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public GamificationViewService(
            RewardAwardRepository awardRepository,
            ExpeditionStateRepository stateRepository,
            ExpeditionContributionRepository contributionRepository,
            RewardCatalog rewardCatalog,
            AchievementService achievementService,
            JdbcTemplate jdbcTemplate
    ) {
        this(awardRepository, stateRepository, contributionRepository, rewardCatalog, achievementService,
                jdbcTemplate, Clock.system(MOSCOW));
    }

    GamificationViewService(
            RewardAwardRepository awardRepository,
            ExpeditionStateRepository stateRepository,
            ExpeditionContributionRepository contributionRepository,
            RewardCatalog rewardCatalog,
            AchievementService achievementService,
            JdbcTemplate jdbcTemplate,
            Clock clock
    ) {
        this.awardRepository = Objects.requireNonNull(awardRepository, "awardRepository");
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
        this.contributionRepository = Objects.requireNonNull(contributionRepository, "contributionRepository");
        this.rewardCatalog = Objects.requireNonNull(rewardCatalog, "rewardCatalog");
        this.achievementService = Objects.requireNonNull(achievementService, "achievementService");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional(readOnly = true)
    public boolean hasGameState(Long marathonId) {
        return marathonId != null && stateRepository.existsByMarathonId(marathonId);
    }

    @Transactional
    public GamificationCabinetView openCabinet(UserEntity user) {
        LocalDateTime shownAt = LocalDateTime.now(clock);
        return build(user, claimPending(marathon(user).getId(), user.getTelegramId(), shownAt), shownAt.toLocalDate());
    }

    @Transactional(readOnly = true)
    public GamificationCabinetView getCabinet(UserEntity user) {
        return build(user, List.of(), LocalDate.now(clock));
    }

    private GamificationCabinetView build(UserEntity user, List<RewardItem> newlyShown, LocalDate today) {
        MarathonEntity marathon = marathon(user);
        ExpeditionStateEntity state = stateRepository.findFirstByMarathonIdOrderByRuleVersionDesc(marathon.getId())
                .orElseThrow(() -> new IllegalStateException("Gamification is not activated for marathon " + marathon.getId()));
        int rulesVersion = state.getRuleVersion();
        LocalDate evaluationDate = today;
        if (marathon.getDateEnd() != null && evaluationDate.isAfter(marathon.getDateEnd().toLocalDate())) {
            evaluationDate = marathon.getDateEnd().toLocalDate();
        }
        boolean finished = marathon.getStatus() == MarathonStatus.FINISHED
                || marathon.getDateEnd() != null && today.isAfter(marathon.getDateEnd().toLocalDate());
        RewardEvaluationContext context = achievementService.buildContext(
                marathon,
                user.getTelegramId(),
                rulesVersion,
                evaluationDate,
                finished,
                Boolean.TRUE.equals(state.getRestOnSaturday()),
                Boolean.TRUE.equals(state.getRestOnSunday())
        );

        List<RewardAwardRepository.CabinetAwardProjection> stored = awardRepository.findCabinetAwards(
                marathon.getId(), user.getTelegramId());
        Map<RewardCode, Long> counts = new LinkedHashMap<>();
        stored.forEach(award -> counts.merge(code(award.getRewardCode()), 1L, Long::sum));
        List<MedalItem> medals = counts.entrySet().stream()
                .filter(entry -> rewardCatalog.definition(rulesVersion, entry.getKey()).repeatable())
                .map(entry -> {
                    RewardDefinition definition = rewardCatalog.definition(rulesVersion, entry.getKey());
                    return new MedalItem(definition.emoji(), definition.title(), entry.getValue());
                })
                .toList();
        List<AchievementItem> achievements = stored.stream()
                .filter(award -> !rewardCatalog.definition(rulesVersion, code(award.getRewardCode())).repeatable())
                .map(award -> {
                    RewardDefinition definition = rewardCatalog.definition(rulesVersion, code(award.getRewardCode()));
                    return new AchievementItem(definition.emoji(), definition.title(), award.getGoalNameSnapshot());
                })
                .toList();

        CabinetStats stats = calculateStats(context.days());
        Set<RewardCode> earnedCodes = EnumSet.noneOf(RewardCode.class);
        earnedCodes.addAll(counts.keySet());
        List<ProgressItem> progress = nearbyProgress(rulesVersion, stats, earnedCodes);
        ExpeditionContributionRepository.CabinetEnergyProjection energy = contributionRepository.findCabinetEnergy(
                marathon.getId(), user.getTelegramId(), rulesVersion, today);
        List<RewardItem> checkpoints = stored.stream()
                .map(award -> code(award.getRewardCode()))
                .filter(code -> code == RewardCode.EXPEDITION_25 || code == RewardCode.EXPEDITION_50
                        || code == RewardCode.EXPEDITION_75 || code == RewardCode.EXPEDITION_100)
                .distinct()
                .sorted(Comparator.comparingInt(GamificationViewService::checkpoint))
                .map(code -> item(rewardCatalog.definition(rulesVersion, code), null))
                .toList();

        return new GamificationCabinetView(
                marathon.getName(),
                finished,
                newlyShown,
                medals,
                achievements,
                progress,
                new StreakView(stats.currentReportStreak(), stats.bestReportStreak(), stats.goalStreaks()),
                new ExpeditionView(
                        state.getDistance(),
                        energy == null || energy.getPersonalEnergy() == null ? 0 : energy.getPersonalEnergy(),
                        energy == null || energy.getTodayEnergy() == null ? 0 : energy.getTodayEnergy(),
                        checkpoints)
        );
    }

    private List<RewardItem> claimPending(Long marathonId, Long userId, LocalDateTime shownAt) {
        return jdbcTemplate.query(
                CLAIM_PENDING,
                (resultSet, rowNumber) -> {
                    RewardCode code = code(resultSet.getString("reward_code"));
                    RewardDefinition definition = rewardCatalog.definition(resultSet.getInt("rule_version"), code);
                    return item(definition, resultSet.getString("goal_name_snapshot"));
                },
                marathonId,
                userId,
                Timestamp.valueOf(shownAt)
        );
    }

    private List<ProgressItem> nearbyProgress(int rulesVersion, CabinetStats stats, Set<RewardCode> earnedCodes) {
        List<ProgressItem> result = new ArrayList<>();
        addNextTier(result, rulesVersion, "REPORT_SERIES", stats.bestReportStreak(), earnedCodes);
        addNextTier(result, rulesVersion, "GOALS_DONE", stats.completedGoals(), earnedCodes);
        addNextTier(result, rulesVersion, "GREEN_DAYS", stats.greenDays(), earnedCodes);
        int bestGoal = stats.goalStreaks().stream().mapToInt(GoalStreak::best).max().orElse(0);
        addNextTier(result, rulesVersion, "GOAL_STREAK", bestGoal, earnedCodes);
        return List.copyOf(result);
    }

    private void addNextTier(
            List<ProgressItem> target,
            int rulesVersion,
            String family,
            long current,
            Set<RewardCode> earnedCodes
    ) {
        rewardCatalog.definitions(rulesVersion).stream()
                .filter(definition -> definition.tier() != null && family.equals(definition.tier().family()))
                .filter(definition -> !earnedCodes.contains(definition.code()))
                .filter(definition -> current < definition.tier().threshold())
                .min(Comparator.comparingInt(definition -> definition.tier().threshold()))
                .ifPresent(definition -> target.add(new ProgressItem(
                        definition.title(), current, definition.tier().threshold())));
    }

    private static CabinetStats calculateStats(List<UserDaySnapshot> days) {
        int currentReport = 0;
        int bestReport = 0;
        long completedGoals = 0;
        long greenDays = 0;
        Map<Long, MutableGoalStreak> goals = new LinkedHashMap<>();
        for (UserDaySnapshot day : days) {
            if (day.configuredRestDay() || day.personalDayOff()) continue;

            if (day.reportGoal().onTime()) currentReport++; else currentReport = 0;
            bestReport = Math.max(bestReport, currentReport);
            Map<Long, UserDaySnapshot.PersonalGoalSnapshot> dayGoals = new LinkedHashMap<>();
            day.personalGoals().forEach(goal -> dayGoals.put(goal.goalId(), goal));
            goals.forEach((goalId, streak) -> {
                if (!dayGoals.containsKey(goalId)) streak.current = 0;
            });
            for (UserDaySnapshot.PersonalGoalSnapshot goal : dayGoals.values()) {
                MutableGoalStreak streak = goals.computeIfAbsent(goal.goalId(), ignored -> new MutableGoalStreak());
                streak.name = goal.goalName();
                if (Boolean.TRUE.equals(goal.done())) {
                    streak.current++;
                    streak.best = Math.max(streak.best, streak.current);
                    completedGoals++;
                } else {
                    streak.current = 0;
                }
            }
            if (Boolean.TRUE.equals(day.reportGoal().done()) && !day.personalGoals().isEmpty()
                    && day.personalGoals().stream().allMatch(goal -> Boolean.TRUE.equals(goal.done()))) {
                greenDays++;
            }
        }
        List<GoalStreak> goalStreaks = goals.values().stream()
                .filter(streak -> streak.best > 0)
                .sorted(Comparator.comparingInt((MutableGoalStreak streak) -> streak.best).reversed()
                        .thenComparing(streak -> streak.name))
                .map(streak -> new GoalStreak(streak.name, streak.current, streak.best))
                .limit(20)
                .toList();
        return new CabinetStats(currentReport, bestReport, completedGoals, greenDays, goalStreaks);
    }

    private static MarathonEntity marathon(UserEntity user) {
        Objects.requireNonNull(user, "user");
        return Objects.requireNonNull(user.getMarathon(), "user.marathon");
    }

    private static RewardCode code(String value) {
        return RewardCode.valueOf(value);
    }

    private static RewardItem item(RewardDefinition definition, String goalName) {
        return new RewardItem(definition.emoji(), definition.title(), goalName);
    }

    private static int checkpoint(RewardCode code) {
        return switch (code) {
            case EXPEDITION_25 -> 25;
            case EXPEDITION_50 -> 50;
            case EXPEDITION_75 -> 75;
            case EXPEDITION_100 -> 100;
            default -> 0;
        };
    }

    private record CabinetStats(
            int currentReportStreak,
            int bestReportStreak,
            long completedGoals,
            long greenDays,
            List<GoalStreak> goalStreaks
    ) {
    }

    private static final class MutableGoalStreak {
        private String name = "Цель";
        private int current;
        private int best;
    }
}
