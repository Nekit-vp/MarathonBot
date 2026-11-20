package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.enumBot.MarathonStatus;
import com.nekitvp.marathonbot.gamification.model.ExpeditionStateEntity;
import com.nekitvp.marathonbot.gamification.model.RewardAwardEntity;
import com.nekitvp.marathonbot.gamification.model.RewardNotificationMode;
import com.nekitvp.marathonbot.gamification.repository.ExpeditionStateRepository;
import com.nekitvp.marathonbot.gamification.repository.RewardAwardRepository;
import com.nekitvp.marathonbot.gamification.rule.RewardCatalog;
import com.nekitvp.marathonbot.gamification.rule.RewardCode;
import com.nekitvp.marathonbot.gamification.rule.RewardDefinition;
import com.nekitvp.marathonbot.gamification.rule.RewardEvaluationContext;
import com.nekitvp.marathonbot.gamification.rule.UserDaySnapshot;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Coordinates deterministic, failure-isolated scheduled gamification work. */
@Slf4j
@Service
public class GamificationSchedulingService {

    private static final LocalTime EARLY_BIRD_CUTOFF = LocalTime.NOON;
    private static final int MIN_LEADER_STREAK = 2;
    private static final List<String> CHECKPOINT_CODES = List.of(
            RewardCode.EXPEDITION_25.name(), RewardCode.EXPEDITION_50.name(),
            RewardCode.EXPEDITION_75.name(), RewardCode.EXPEDITION_100.name());

    private final MarathonRepository marathonRepository;
    private final ExpeditionStateRepository stateRepository;
    private final MarathonParticipationService participationService;
    private final GamificationService gamificationService;
    private final AchievementService achievementService;
    private final RewardAwardRepository awardRepository;
    private final UserRepository userRepository;
    private final RewardCatalog rewardCatalog;
    private final GamificationMessageComposer composer;
    private final GamificationNotificationService notificationService;

    public GamificationSchedulingService(
            MarathonRepository marathonRepository,
            ExpeditionStateRepository stateRepository,
            MarathonParticipationService participationService,
            GamificationService gamificationService,
            AchievementService achievementService,
            RewardAwardRepository awardRepository,
            UserRepository userRepository,
            RewardCatalog rewardCatalog,
            GamificationMessageComposer composer,
            GamificationNotificationService notificationService
    ) {
        this.marathonRepository = Objects.requireNonNull(marathonRepository, "marathonRepository");
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
        this.participationService = Objects.requireNonNull(participationService, "participationService");
        this.gamificationService = Objects.requireNonNull(gamificationService, "gamificationService");
        this.achievementService = Objects.requireNonNull(achievementService, "achievementService");
        this.awardRepository = Objects.requireNonNull(awardRepository, "awardRepository");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.rewardCatalog = Objects.requireNonNull(rewardCatalog, "rewardCatalog");
        this.composer = Objects.requireNonNull(composer, "composer");
        this.notificationService = Objects.requireNonNull(notificationService, "notificationService");
    }

    public void reconcileAfterMidnight(LocalDateTime now) {
        Objects.requireNonNull(now, "now");
        LocalDate activityDate = now.toLocalDate().minusDays(1);
        marathonRepository.findAll().stream()
                .filter(marathon -> eligible(marathon, activityDate))
                .forEach(marathon -> reconcileIsolated(marathon, activityDate, now));
    }

    public void reconcileBeforeFinish(MarathonEntity marathon, LocalDate finalDate, LocalDateTime now) {
        Objects.requireNonNull(marathon, "marathon");
        Objects.requireNonNull(finalDate, "finalDate");
        Objects.requireNonNull(now, "now");
        if (!Boolean.TRUE.equals(marathon.getGamificationEnabled())) {
            return;
        }
        reconcileMarathon(marathon, finalDate, now, true);
    }

    @Transactional(readOnly = true)
    public String statisticsProgress(MarathonEntity marathon) {
        if (marathon == null || !Boolean.TRUE.equals(marathon.getGamificationEnabled())) {
            return "";
        }
        return stateRepository.findFirstByMarathonIdOrderByRuleVersionDesc(marathon.getId())
                .map(state -> composer.composeStatisticsProgress(
                        state.getDistance().setScale(0, RoundingMode.DOWN).intValue()))
                .orElse("");
    }

    private void reconcileIsolated(MarathonEntity marathon, LocalDate activityDate, LocalDateTime now) {
        try {
            reconcileMarathon(marathon, activityDate, now, false);
        } catch (RuntimeException exception) {
            log.error("Scheduled gamification reconciliation failed for marathon {}", marathon.getId(), exception);
        }
    }

    /**
     * Publishes the single daily group message. The outbox key is the calendar date, so a checkpoint and a
     * Monday digest merge into one message instead of competing for the standalone-message budget.
     */
    public void publishDailyDigest(LocalDateTime now) {
        Objects.requireNonNull(now, "now");
        LocalDate activityDate = now.toLocalDate().minusDays(1);
        marathonRepository.findAll().stream()
                .filter(marathon -> eligible(marathon, activityDate))
                .forEach(marathon -> publishIsolated(marathon, activityDate, now));
    }

    private void publishIsolated(MarathonEntity marathon, LocalDate activityDate, LocalDateTime now) {
        try {
            if (marathon.getGroupId() == null) {
                return;
            }
            ExpeditionStateEntity state = stateRepository
                    .findFirstByMarathonIdOrderByRuleVersionDesc(marathon.getId()).orElseThrow();
            RewardCode checkpoint = highestUnannouncedCheckpoint(marathon.getId(), state);

            List<String> extraBlocks = new ArrayList<>();
            if (now.getDayOfWeek() == DayOfWeek.MONDAY) {
                String weekly = composeCompletedWeekBlock(marathon, state, now);
                if (!weekly.isBlank()) {
                    extraBlocks.add(weekly);
                }
            }
            if (checkpoint != null) {
                extraBlocks.add(composeCheckpoint(marathon, state, checkpoint));
            }

            List<GamificationMessageComposer.Highlight> highlights = highlightsBetween(
                    marathon, state.getRuleVersion(),
                    activityDate.atStartOfDay(), activityDate.plusDays(1).atStartOfDay());
            String progress = composer.composeStatisticsProgress(
                    state.getDistance().setScale(0, RoundingMode.DOWN).intValue());
            String leader = greenLeaderLine(marathon, state, activityDate);
            String tail = leader.isBlank() ? progress : progress + "\n" + leader;

            List<String> pages = composer.composeDailyDigest(
                    activityDate, highlights, tail, String.join("\n\n", extraBlocks));
            if (pages.size() > 1) {
                log.warn("Daily digest exceeded one Telegram message and was truncated: marathon={}, date={},"
                                + " pages={}, rewards={}",
                        marathon.getId(), activityDate, pages.size(), highlights.size());
            }
            notificationService.enqueueDailyHighlight(
                    marathon.getId(), now.toLocalDate(), marathon.getGroupId(), pages.getFirst(), now);

            markCheckpointAnnounced(state, checkpoint);
            stateRepository.save(state);
        } catch (RuntimeException exception) {
            log.error("Daily gamification digest failed for marathon {}", marathon.getId(), exception);
        }
    }

    /** Longest active green run in the team, shown only when it is worth celebrating. */
    private String greenLeaderLine(MarathonEntity marathon, ExpeditionStateEntity state, LocalDate activityDate) {
        String bestName = null;
        int bestStreak = 0;
        for (Long userId : participationService.rosterAt(marathon.getId(), activityDate.atTime(LocalTime.MAX))) {
            RewardEvaluationContext context = achievementService.buildContext(
                    marathon, userId, state.getRuleVersion(), activityDate, false,
                    Boolean.TRUE.equals(state.getRestOnSaturday()),
                    Boolean.TRUE.equals(state.getRestOnSunday()));
            int streak = AchievementService.dayStats(context).greenStreak();
            if (streak > bestStreak) {
                bestStreak = streak;
                bestName = userRepository.findById(userId)
                        .map(GamificationSchedulingService::effectiveName).orElse("Участник");
            }
        }
        return bestStreak >= MIN_LEADER_STREAK && bestName != null
                ? composer.composeGreenLeader(bestName, bestStreak)
                : "";
    }

    private void reconcileMarathon(
            MarathonEntity marathon,
            LocalDate activityDate,
            LocalDateTime now,
            boolean marathonFinished
    ) {
        ExpeditionStateEntity state = stateRepository.findFirstByMarathonIdOrderByRuleVersionDesc(marathon.getId())
                .orElseThrow(() -> new IllegalStateException("Expedition is not activated for marathon " + marathon.getId()));
        for (Long userId : participationService.rosterAt(marathon.getId(), activityDate.atTime(LocalTime.MAX))) {
            gamificationService.recalculateSafely(
                    new GamificationService.RecalculationRequest(
                            marathon.getId(), userId, state.getRuleVersion(), activityDate, now,
                            RewardNotificationMode.PENDING, marathonFinished, false));
        }
        gamificationService.recalculateTeamSafely(
                marathon.getId(), state.getRuleVersion(), activityDate, now, RewardNotificationMode.PENDING);
    }

    /**
     * Weekly block for a completed Mon–Sun week. The week only has to <em>overlap</em> the route: requiring it to
     * be fully contained silently dropped the first and the last week of every marathon.
     */
    private String composeCompletedWeekBlock(
            MarathonEntity marathon,
            ExpeditionStateEntity state,
            LocalDateTime now
    ) {
        LocalDate weekEnd = now.toLocalDate().minusDays(1);
        LocalDate weekStart = weekEnd.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (weekEnd.getDayOfWeek() != DayOfWeek.SUNDAY
                || state.getRouteStartDate().isAfter(weekEnd)
                || state.getRouteEndDate().isBefore(weekStart)) {
            return "";
        }
        String weekKey = isoWeekKey(weekStart);
        if (weekKey.equals(state.getLastDigestWeek())) {
            return "";
        }

        LocalDate from = maxDate(weekStart, state.getRouteStartDate());
        LocalDate to = minDate(weekEnd, state.getRouteEndDate());
        awardEarlyBird(marathon, state, from, to, now);
        List<GamificationMessageComposer.Highlight> highlights = highlightsBetween(
                marathon, state.getRuleVersion(), from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        state.setLastDigestWeek(weekKey);
        return composer.composeWeeklySummary(from, to, highlights);
    }

    private static LocalDate maxDate(LocalDate left, LocalDate right) {
        return left.isAfter(right) ? left : right;
    }

    private static LocalDate minDate(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    private void awardEarlyBird(
            MarathonEntity marathon,
            ExpeditionStateEntity state,
            LocalDate weekStart,
            LocalDate weekEnd,
            LocalDateTime now
    ) {
        Set<Long> weeklyRoster = new LinkedHashSet<>();
        for (LocalDate date = weekStart; !date.isAfter(weekEnd); date = date.plusDays(1)) {
            weeklyRoster.addAll(participationService.rosterAt(marathon.getId(), date.atTime(LocalTime.MAX)));
        }
        weeklyRoster.stream()
                .map(userId -> earliestMorningReport(marathon, state, userId, weekStart, weekEnd))
                .flatMap(Optional::stream)
                .min(Comparator.comparing(EarlyReport::time)
                        .thenComparing(EarlyReport::date)
                        .thenComparing(EarlyReport::userId))
                .ifPresent(winner -> achievementService.persistWeeklyEarlyBird(
                        marathon.getId(), winner.userId(), state.getRuleVersion(), weekEnd,
                        RewardNotificationMode.PENDING, now));
    }

    private Optional<EarlyReport> earliestMorningReport(
            MarathonEntity marathon,
            ExpeditionStateEntity state,
            Long userId,
            LocalDate weekStart,
            LocalDate weekEnd
    ) {
        RewardEvaluationContext context = achievementService.buildContext(
                marathon, userId, state.getRuleVersion(), weekEnd, false,
                Boolean.TRUE.equals(state.getRestOnSaturday()), Boolean.TRUE.equals(state.getRestOnSunday()));
        return context.days().stream()
                .filter(day -> !day.activityDate().isBefore(weekStart) && !day.activityDate().isAfter(weekEnd))
                .filter(day -> day.reportGoal().onTime())
                .filter(day -> day.reportGoal().submittedAt() != null)
                .filter(day -> day.reportGoal().submittedAt().isBefore(EARLY_BIRD_CUTOFF))
                .map(day -> new EarlyReport(userId, day.activityDate(), day.reportGoal().submittedAt()))
                .min(Comparator.comparing(EarlyReport::time).thenComparing(EarlyReport::date));
    }

    private List<GamificationMessageComposer.Highlight> highlightsBetween(
            MarathonEntity marathon,
            int rulesVersion,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive
    ) {
        List<RewardAwardEntity> awards = awardRepository
                .findAllByMarathonIdAndRuleVersionAndEarnedOnBetweenAndNotificationModeNotOrderByEarnedOnAscIdAsc(
                        marathon.getId(), rulesVersion, fromInclusive.toLocalDate(),
                        toExclusive.minusNanos(1).toLocalDate(), RewardNotificationMode.BACKFILL);
        Map<Long, String> names = new LinkedHashMap<>();
        userRepository.findAllById(awards.stream().map(RewardAwardEntity::getUserId).distinct().toList())
                .forEach(user -> names.put(user.getTelegramId(), effectiveName(user)));
        List<GamificationMessageComposer.Highlight> highlights = new ArrayList<>();
        for (RewardAwardEntity award : awards) {
            RewardDefinition definition = rewardCatalog.definition(rulesVersion, RewardCode.valueOf(award.getRewardCode()));
            highlights.add(new GamificationMessageComposer.Highlight(
                    names.getOrDefault(award.getUserId(), "Участник"),
                    definition.emoji(), definition.title(), definition.shortHint()));
        }
        return List.copyOf(highlights);
    }

    private String composeCheckpoint(MarathonEntity marathon, ExpeditionStateEntity state, RewardCode checkpoint) {
        RewardDefinition definition = rewardCatalog.definition(state.getRuleVersion(), checkpoint);
        return composer.composeCheckpointCelebration(
                marathon.getName(), checkpointPercent(checkpoint), definition.emoji(), definition.title());
    }

    private boolean eligible(MarathonEntity marathon, LocalDate activityDate) {
        if (!Boolean.TRUE.equals(marathon.getGamificationEnabled())) {
            return false;
        }
        if (marathon.getStatus() == MarathonStatus.ACTIVE) {
            return true;
        }
        return marathon.getStatus() == MarathonStatus.FINISHED && marathon.getDateEnd() != null
                && marathon.getDateEnd().toLocalDate().equals(activityDate);
    }

    private RewardCode highestUnannouncedCheckpoint(Long marathonId, ExpeditionStateEntity state) {
        return awardRepository.findAllByMarathonIdAndRuleVersionAndRewardCodeIn(
                        marathonId, state.getRuleVersion(), CHECKPOINT_CODES).stream()
                .map(RewardAwardEntity::getRewardCode)
                .map(RewardCode::valueOf)
                .filter(code -> checkpointPercent(code) > state.getLastAnnouncedCheckpoint())
                .max(Comparator.comparingInt(GamificationSchedulingService::checkpointPercent))
                .orElse(null);
    }

    private static void markCheckpointAnnounced(ExpeditionStateEntity state, RewardCode checkpoint) {
        if (checkpoint != null) {
            state.setLastAnnouncedCheckpoint(Math.max(
                    state.getLastAnnouncedCheckpoint(), checkpointPercent(checkpoint)));
        }
    }

    private static int checkpointPercent(RewardCode code) {
        return switch (code) {
            case EXPEDITION_25 -> 25;
            case EXPEDITION_50 -> 50;
            case EXPEDITION_75 -> 75;
            case EXPEDITION_100 -> 100;
            default -> throw new IllegalArgumentException("Not an expedition checkpoint: " + code);
        };
    }

    private static String isoWeekKey(LocalDate date) {
        return "%d-W%02d".formatted(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    private static String effectiveName(UserEntity user) {
        String name = user.getEffectiveName();
        return name == null || name.isBlank() ? "Участник" : name;
    }

    private record EarlyReport(Long userId, LocalDate date, LocalTime time) {
    }
}
