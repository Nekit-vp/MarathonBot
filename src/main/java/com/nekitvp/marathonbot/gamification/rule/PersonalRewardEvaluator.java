package com.nekitvp.marathonbot.gamification.rule;

import com.nekitvp.marathonbot.gamification.model.RewardKind;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure launch-rules evaluator for repeatable personal medals and cumulative achievements. */
@Component
public final class PersonalRewardEvaluator {

    private static final LocalTime EARLY_START_END = LocalTime.NOON;
    private static final LocalTime BEFORE_REMINDER_END = LocalTime.of(22, 0);

    private static final Set<RewardCode> SUPPORTED_CODES = EnumSet.of(
            RewardCode.STRONG_DAY,
            RewardCode.GREEN_DAY,
            RewardCode.EARLY_START,
            RewardCode.BEFORE_REMINDER,
            RewardCode.COMEBACK,
            RewardCode.FIRST_STEP,
            RewardCode.REPORT_STREAK_3,
            RewardCode.REPORT_STREAK_7,
            RewardCode.REPORT_STREAK_14,
            RewardCode.REPORT_STREAK_21,
            RewardCode.GOAL_STREAK_7,
            RewardCode.GOAL_STREAK_14,
            RewardCode.GOAL_STREAK_21,
            RewardCode.GOALS_DONE_25,
            RewardCode.GOALS_DONE_50,
            RewardCode.GOALS_DONE_100,
            RewardCode.GOALS_DONE_200,
            RewardCode.GREEN_DAYS_3,
            RewardCode.GREEN_DAYS_7,
            RewardCode.GREEN_DAYS_14,
            RewardCode.GREEN_STREAK_3,
            RewardCode.GREEN_STREAK_5,
            RewardCode.GREEN_STREAK_10
    );

    private final RewardCatalog rewardCatalog;

    public PersonalRewardEvaluator(RewardCatalog rewardCatalog) {
        this.rewardCatalog = Objects.requireNonNull(rewardCatalog, "rewardCatalog");
    }

    /**
     * Evaluates only facts inside the marathon window and at or before the supplied evaluation date.
     * Re-evaluating the same immutable context returns equal occurrences in the same order.
     */
    public List<RewardOccurrence> evaluate(RewardEvaluationContext context) {
        Objects.requireNonNull(context, "context");
        validateRulesVersion(context.rulesVersion());

        List<UserDaySnapshot> days = relevantDays(context);
        List<RewardOccurrence> rewards = new ArrayList<>();
        EvaluationState state = new EvaluationState();

        for (UserDaySnapshot day : days) {
            if (state.previousCalendarDate != null
                    && day.activityDate().isAfter(state.previousCalendarDate.plusDays(1))) {
                state.breakAllSeries();
            }
            state.previousCalendarDate = day.activityDate();
            if (day.configuredRestDay() || day.personalDayOff()) {
                continue;
            }

            evaluateDailyMedals(context, day, state.previousActiveDay, rewards);
            evaluateReportSeries(context, day, state, rewards);
            evaluateGoalSeries(context, day, state, rewards);
            evaluateCountMilestones(context, day, state, rewards);
            state.previousActiveDay = day;
        }

        return List.copyOf(rewards);
    }

    private void validateRulesVersion(int rulesVersion) {
        if (rulesVersion != RewardCatalog.RULES_VERSION_1) {
            throw new IllegalArgumentException("Unsupported personal reward rules version: " + rulesVersion);
        }
    }

    private static List<UserDaySnapshot> relevantDays(RewardEvaluationContext context) {
        LocalDate lastDate = context.evaluationDate().isBefore(context.marathonEndDate())
                ? context.evaluationDate()
                : context.marathonEndDate();
        if (lastDate.isBefore(context.marathonStartDate())) {
            return List.of();
        }
        return context.days().stream()
                .filter(day -> !day.activityDate().isBefore(context.marathonStartDate()))
                .filter(day -> !day.activityDate().isAfter(lastDate))
                .toList();
    }

    private void evaluateDailyMedals(
            RewardEvaluationContext context,
            UserDaySnapshot day,
            UserDaySnapshot previousActiveDay,
            List<RewardOccurrence> rewards
    ) {
        if (isValidReport(day) && !day.personalGoals().isEmpty()) {
            long completed = completedGoalCount(day);
            long total = day.personalGoals().size();
            if (completed == total) {
                addDateOccurrence(context, RewardCode.GREEN_DAY, day.activityDate(), rewards);
            } else if (completed * 100 >= total * 80) {
                addDateOccurrence(context, RewardCode.STRONG_DAY, day.activityDate(), rewards);
            }
        }

        if (day.reportGoal().onTime() && day.reportGoal().submittedAt() != null) {
            LocalTime submittedAt = day.reportGoal().submittedAt();
            if (submittedAt.isBefore(EARLY_START_END)) {
                addDateOccurrence(context, RewardCode.EARLY_START, day.activityDate(), rewards);
            } else if (submittedAt.isBefore(BEFORE_REMINDER_END)) {
                addDateOccurrence(context, RewardCode.BEFORE_REMINDER, day.activityDate(), rewards);
            }
        }

        if (day.reportGoal().onTime()
                && isGreenDay(day)
                && previousActiveDay != null
                && hasIncompletePersonalGoal(previousActiveDay)) {
            addDateOccurrence(context, RewardCode.COMEBACK, day.activityDate(), rewards);
        }
    }

    private void evaluateReportSeries(
            RewardEvaluationContext context,
            UserDaySnapshot day,
            EvaluationState state,
            List<RewardOccurrence> rewards
    ) {
        if (!day.reportGoal().onTime()) {
            state.reportStreak = 0;
            return;
        }

        state.reportStreak++;
        if (!state.firstStepEmitted) {
            addTierOccurrence(context, RewardCode.FIRST_STEP, day.activityDate(), rewards);
            state.firstStepEmitted = true;
        }
        emitReachedReportTiers(context, day.activityDate(), state, rewards);
    }

    private void emitReachedReportTiers(
            RewardEvaluationContext context,
            LocalDate earnedOn,
            EvaluationState state,
            List<RewardOccurrence> rewards
    ) {
        for (RewardDefinition definition : rewardCatalog.tier("REPORT_SERIES")) {
            RewardCode code = definition.code();
            if (code == RewardCode.FIRST_STEP
                    || state.reportStreak < definition.tier().threshold()
                    || !state.emittedReportTiers.add(code)) {
                continue;
            }
            addOccurrence(context, definition, OccurrenceKeyContext.empty(), earnedOn, null, null, rewards);
        }
    }

    private void evaluateGoalSeries(
            RewardEvaluationContext context,
            UserDaySnapshot day,
            EvaluationState state,
            List<RewardOccurrence> rewards
    ) {
        Map<Long, UserDaySnapshot.PersonalGoalSnapshot> goalsById = goalsById(day);
        state.goalStreaks.keySet().stream()
                .filter(goalId -> !goalsById.containsKey(goalId))
                .forEach(goalId -> state.goalStreaks.get(goalId).current = 0);

        goalsById.values().stream()
                .sorted(Comparator.comparing(UserDaySnapshot.PersonalGoalSnapshot::goalId))
                .forEach(goal -> evaluateGoalSeries(context, day.activityDate(), goal, state, rewards));
    }

    private void evaluateGoalSeries(
            RewardEvaluationContext context,
            LocalDate earnedOn,
            UserDaySnapshot.PersonalGoalSnapshot goal,
            EvaluationState state,
            List<RewardOccurrence> rewards
    ) {
        GoalStreakState goalState = state.goalStreaks.computeIfAbsent(goal.goalId(), ignored -> new GoalStreakState());
        if (!Boolean.TRUE.equals(goal.done())) {
            goalState.current = 0;
            return;
        }

        goalState.current++;
        for (RewardDefinition definition : rewardCatalog.tier("GOAL_STREAK")) {
            if (goalState.current < definition.tier().threshold()
                    || !goalState.emittedTiers.add(definition.code())) {
                continue;
            }
            addOccurrence(
                    context,
                    definition,
                    OccurrenceKeyContext.forGoal(goal.goalId()),
                    earnedOn,
                    goal.goalId(),
                    goal.goalName(),
                    rewards
            );
        }
    }

    private void evaluateCountMilestones(
            RewardEvaluationContext context,
            UserDaySnapshot day,
            EvaluationState state,
            List<RewardOccurrence> rewards
    ) {
        state.completedGoalCount += completedGoalCount(day);
        emitReachedCountTiers(
                context,
                "GOALS_DONE",
                state.completedGoalCount,
                day.activityDate(),
                state.emittedGoalTotalTiers,
                rewards
        );

        if (isGreenDay(day)) {
            state.greenDayCount++;
            emitReachedCountTiers(
                    context,
                    "GREEN_DAYS",
                    state.greenDayCount,
                    day.activityDate(),
                    state.emittedGreenDayTiers,
                    rewards
            );
            state.greenStreak++;
            emitReachedCountTiers(
                    context,
                    "GREEN_STREAK",
                    state.greenStreak,
                    day.activityDate(),
                    state.emittedGreenStreakTiers,
                    rewards
            );
        } else {
            state.greenStreak = 0;
        }
    }

    private void emitReachedCountTiers(
            RewardEvaluationContext context,
            String family,
            long count,
            LocalDate earnedOn,
            Set<RewardCode> emitted,
            List<RewardOccurrence> rewards
    ) {
        for (RewardDefinition definition : rewardCatalog.tier(family)) {
            if (count < definition.tier().threshold() || !emitted.add(definition.code())) {
                continue;
            }
            addOccurrence(context, definition, OccurrenceKeyContext.empty(), earnedOn, null, null, rewards);
        }
    }

    private void addDateOccurrence(
            RewardEvaluationContext context,
            RewardCode code,
            LocalDate earnedOn,
            List<RewardOccurrence> rewards
    ) {
        RewardDefinition definition = rewardCatalog.definition(context.rulesVersion(), code);
        addOccurrence(context, definition, OccurrenceKeyContext.onDate(earnedOn), earnedOn, null, null, rewards);
    }

    private void addTierOccurrence(
            RewardEvaluationContext context,
            RewardCode code,
            LocalDate earnedOn,
            List<RewardOccurrence> rewards
    ) {
        RewardDefinition definition = rewardCatalog.definition(context.rulesVersion(), code);
        addOccurrence(context, definition, OccurrenceKeyContext.empty(), earnedOn, null, null, rewards);
    }

    private static void addOccurrence(
            RewardEvaluationContext context,
            RewardDefinition definition,
            OccurrenceKeyContext occurrenceContext,
            LocalDate earnedOn,
            Long goalIdSnapshot,
            String goalNameSnapshot,
            List<RewardOccurrence> rewards
    ) {
        if (!SUPPORTED_CODES.contains(definition.code())) {
            throw new IllegalArgumentException("Unsupported personal reward code: " + definition.code());
        }
        rewards.add(new RewardOccurrence(
                context.rulesVersion(),
                definition.code(),
                definition.kind(),
                definition.occurrenceKey(occurrenceContext),
                earnedOn,
                goalIdSnapshot,
                goalNameSnapshot
        ));
    }

    private static Map<Long, UserDaySnapshot.PersonalGoalSnapshot> goalsById(UserDaySnapshot day) {
        Map<Long, UserDaySnapshot.PersonalGoalSnapshot> goalsById = new LinkedHashMap<>();
        for (UserDaySnapshot.PersonalGoalSnapshot goal : day.personalGoals()) {
            if (goalsById.putIfAbsent(goal.goalId(), goal) != null) {
                throw new IllegalArgumentException(
                        "Duplicate goal snapshot " + goal.goalId() + " on " + day.activityDate());
            }
        }
        return goalsById;
    }

    private static long completedGoalCount(UserDaySnapshot day) {
        return day.personalGoals().stream()
                .filter(goal -> Boolean.TRUE.equals(goal.done()))
                .count();
    }

    private static boolean isValidReport(UserDaySnapshot day) {
        return day.reportSubmitted();
    }

    private static boolean isGreenDay(UserDaySnapshot day) {
        return isValidReport(day)
                && !day.personalGoals().isEmpty()
                && completedGoalCount(day) == day.personalGoals().size();
    }

    private static boolean hasIncompletePersonalGoal(UserDaySnapshot day) {
        return !day.personalGoals().isEmpty()
                && day.personalGoals().stream().anyMatch(goal -> !Boolean.TRUE.equals(goal.done()));
    }

    private static final class EvaluationState {
        private LocalDate previousCalendarDate;
        private UserDaySnapshot previousActiveDay;
        private int reportStreak;
        private boolean firstStepEmitted;
        private long completedGoalCount;
        private int greenDayCount;
        private int greenStreak;
        private final Set<RewardCode> emittedReportTiers = EnumSet.noneOf(RewardCode.class);
        private final Set<RewardCode> emittedGoalTotalTiers = EnumSet.noneOf(RewardCode.class);
        private final Set<RewardCode> emittedGreenDayTiers = EnumSet.noneOf(RewardCode.class);
        private final Set<RewardCode> emittedGreenStreakTiers = EnumSet.noneOf(RewardCode.class);
        private final Map<Long, GoalStreakState> goalStreaks = new HashMap<>();

        private void breakAllSeries() {
            reportStreak = 0;
            greenStreak = 0;
            previousActiveDay = null;
            goalStreaks.values().forEach(goalState -> goalState.current = 0);
        }
    }

    private static final class GoalStreakState {
        private int current;
        private final Set<RewardCode> emittedTiers = EnumSet.noneOf(RewardCode.class);
    }

    /** Persistence-ready description of one deterministic award occurrence. */
    public record RewardOccurrence(
            int rulesVersion,
            RewardCode code,
            RewardKind kind,
            String occurrenceKey,
            LocalDate earnedOn,
            Long goalIdSnapshot,
            String goalNameSnapshot
    ) {
        public RewardOccurrence {
            if (rulesVersion != RewardCatalog.RULES_VERSION_1) {
                throw new IllegalArgumentException("Only launch rules version is supported");
            }
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(kind, "kind");
            if (occurrenceKey == null || occurrenceKey.isBlank()) {
                throw new IllegalArgumentException("occurrenceKey must be visible");
            }
            Objects.requireNonNull(earnedOn, "earnedOn");
            if ((goalIdSnapshot == null) != (goalNameSnapshot == null)) {
                throw new IllegalArgumentException("Goal ID and name snapshots must be present together");
            }
            if (goalIdSnapshot != null && (goalIdSnapshot <= 0 || goalNameSnapshot.isBlank())) {
                throw new IllegalArgumentException("Goal snapshots must be valid");
            }
        }
    }
}
