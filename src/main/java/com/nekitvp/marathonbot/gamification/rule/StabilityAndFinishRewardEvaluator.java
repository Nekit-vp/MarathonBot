package com.nekitvp.marathonbot.gamification.rule;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure evaluator for the launch-version stability and marathon-finish achievements. */
@Component
public final class StabilityAndFinishRewardEvaluator {

    private static final int STABILITY_ACTIVE_DAYS = 7;
    private static final int STABILITY_PERCENT = 80;
    private static final int FINISH_ACTIVE_DAYS = 5;
    private static final int FINISH_PERCENT = 90;

    private final RewardCatalog rewardCatalog;

    public StabilityAndFinishRewardEvaluator(RewardCatalog rewardCatalog) {
        this.rewardCatalog = Objects.requireNonNull(rewardCatalog, "rewardCatalog");
    }

    /** Evaluates immutable facts without repository access or wall-clock reads. */
    public List<PersonalRewardEvaluator.RewardOccurrence> evaluate(RewardEvaluationContext context) {
        Objects.requireNonNull(context, "context");
        validateRulesVersion(context.rulesVersion());

        List<UserDaySnapshot> activeDays = relevantActiveDays(context);
        List<PersonalRewardEvaluator.RewardOccurrence> rewards = new ArrayList<>(3);
        evaluateStability(context, activeDays, rewards);
        evaluateFullDistance(context, activeDays, rewards);
        evaluateFinish(context, activeDays, rewards);
        return List.copyOf(rewards);
    }

    private void evaluateFullDistance(
            RewardEvaluationContext context,
            List<UserDaySnapshot> activeDays,
            List<PersonalRewardEvaluator.RewardOccurrence> rewards
    ) {
        if (!isFinishedWindow(context) || activeDays.isEmpty() || !hasNoMissedReports(activeDays)) {
            return;
        }
        RewardDefinition definition = rewardCatalog.definition(context.rulesVersion(), RewardCode.FULL_DISTANCE);
        rewards.add(occurrence(context, definition, OccurrenceKeyContext.empty(), context.marathonEndDate()));
    }

    private static void validateRulesVersion(int rulesVersion) {
        if (rulesVersion != RewardCatalog.RULES_VERSION_1) {
            throw new IllegalArgumentException("Unsupported stability and finish rules version: " + rulesVersion);
        }
    }

    private static List<UserDaySnapshot> relevantActiveDays(RewardEvaluationContext context) {
        LocalDate lastDate = context.evaluationDate().isBefore(context.marathonEndDate())
                ? context.evaluationDate()
                : context.marathonEndDate();
        if (lastDate.isBefore(context.marathonStartDate())) {
            return List.of();
        }
        return context.days().stream()
                .filter(day -> !day.activityDate().isBefore(context.marathonStartDate()))
                .filter(day -> !day.activityDate().isAfter(lastDate))
                .filter(day -> !day.configuredRestDay() && !day.personalDayOff())
                .toList();
    }

    private void evaluateStability(
            RewardEvaluationContext context,
            List<UserDaySnapshot> activeDays,
            List<PersonalRewardEvaluator.RewardOccurrence> rewards
    ) {
        for (int endExclusive = STABILITY_ACTIVE_DAYS; endExclusive <= activeDays.size(); endExclusive++) {
            List<UserDaySnapshot> window = activeDays.subList(endExclusive - STABILITY_ACTIVE_DAYS, endExclusive);
            if (hasNoMissedReports(window) && reachesPercentage(window, STABILITY_PERCENT)) {
                RewardDefinition definition = rewardCatalog.definition(context.rulesVersion(), RewardCode.STABILITY);
                rewards.add(occurrence(
                        context,
                        definition,
                        OccurrenceKeyContext.empty(),
                        window.getLast().activityDate()
                ));
                return;
            }
        }
    }

    private void evaluateFinish(
            RewardEvaluationContext context,
            List<UserDaySnapshot> activeDays,
            List<PersonalRewardEvaluator.RewardOccurrence> rewards
    ) {
        if (!isFinishedWindow(context)
                || activeDays.size() < FINISH_ACTIVE_DAYS) {
            return;
        }

        List<UserDaySnapshot> finalWindow = activeDays.subList(activeDays.size() - FINISH_ACTIVE_DAYS, activeDays.size());
        if (!hasNoMissedReports(finalWindow) || !reachesPercentage(finalWindow, FINISH_PERCENT)) {
            return;
        }

        RewardDefinition definition = rewardCatalog.definition(context.rulesVersion(), RewardCode.PUSHED_TO_FINISH);
        rewards.add(occurrence(
                context,
                definition,
                OccurrenceKeyContext.empty(),
                context.marathonEndDate()
        ));
    }

    private static boolean isFinishedWindow(RewardEvaluationContext context) {
        return context.marathonFinished() && !context.evaluationDate().isBefore(context.marathonEndDate());
    }

    private static boolean hasNoMissedReports(List<UserDaySnapshot> days) {
        return days.stream().allMatch(day -> Boolean.TRUE.equals(day.reportGoal().done()));
    }

    private static boolean reachesPercentage(List<UserDaySnapshot> days, int requiredPercent) {
        long totalGoals = days.stream().mapToLong(day -> day.personalGoals().size()).sum();
        if (totalGoals == 0) {
            return false;
        }
        long completedGoals = days.stream()
                .flatMap(day -> day.personalGoals().stream())
                .filter(goal -> Boolean.TRUE.equals(goal.done()))
                .count();
        return completedGoals * 100 >= totalGoals * requiredPercent;
    }

    private static PersonalRewardEvaluator.RewardOccurrence occurrence(
            RewardEvaluationContext context,
            RewardDefinition definition,
            OccurrenceKeyContext occurrenceContext,
            LocalDate earnedOn
    ) {
        return new PersonalRewardEvaluator.RewardOccurrence(
                context.rulesVersion(),
                definition.code(),
                definition.kind(),
                definition.occurrenceKey(occurrenceContext),
                earnedOn,
                null,
                null
        );
    }
}
