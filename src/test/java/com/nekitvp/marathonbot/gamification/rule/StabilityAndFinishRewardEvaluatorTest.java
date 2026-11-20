package com.nekitvp.marathonbot.gamification.rule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class StabilityAndFinishRewardEvaluatorTest {

    private static final LocalDate START = LocalDate.of(2026, 8, 1);
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private final RewardCatalog catalog = new RewardCatalog();
    private final StabilityAndFinishRewardEvaluator evaluator = new StabilityAndFinishRewardEvaluator(catalog);

    @ParameterizedTest
    @MethodSource("stabilityThresholdCases")
    void awardsStabilityAtEightyAndOneHundredPercentButNotAtSeventyNine(
            int completedGoals,
            boolean expected
    ) {
        List<UserDaySnapshot> days = sevenActiveDays(completedGoals, 100);

        assertThat(codes(evaluator.evaluate(context(days, START.plusDays(6), false)))
                .contains(RewardCode.STABILITY)).isEqualTo(expected);
    }

    static Stream<Arguments> stabilityThresholdCases() {
        return Stream.of(
                Arguments.of(79, false),
                Arguments.of(80, true),
                Arguments.of(100, true)
        );
    }

    @Test
    void stabilitySpansConfiguredRestAndPersonalDayOffAndAcceptsLateReport() {
        List<UserDaySnapshot> days = new ArrayList<>();
        days.add(completedDay(START, 1, true));
        days.add(UserDaySnapshot.configuredRest(START.plusDays(1)));
        days.add(completedDay(START.plusDays(2), 1, true));
        days.add(UserDaySnapshot.personalDayOff(START.plusDays(3)));
        days.add(completedDay(START.plusDays(4), 1, false));
        days.add(completedDay(START.plusDays(5), 1, true));
        days.add(completedDay(START.plusDays(6), 1, true));
        days.add(completedDay(START.plusDays(7), 1, true));
        days.add(completedDay(START.plusDays(8), 1, true));

        List<PersonalRewardEvaluator.RewardOccurrence> rewards =
                evaluator.evaluate(context(days, START.plusDays(8), false));

        assertThat(rewards).filteredOn(reward -> reward.code() == RewardCode.STABILITY)
                .singleElement()
                .satisfies(reward -> {
                    assertThat(reward.earnedOn()).isEqualTo(START.plusDays(8));
                    assertThat(reward.occurrenceKey()).isEqualTo("tier:STABILITY");
                    assertThat(reward.kind()).isEqualTo(catalog.definition(RewardCode.STABILITY).kind());
                });
    }

    @Test
    void stabilityRejectsASevenActiveDayWindowWithMissedReport() {
        List<UserDaySnapshot> days = new ArrayList<>(sevenActiveDays(100, 100));
        UserDaySnapshot original = days.get(3);
        days.set(3, new UserDaySnapshot(
                original.activityDate(),
                false,
                false,
                UserDaySnapshot.ReportGoalSnapshot.absent(),
                original.personalGoals()
        ));

        assertThat(codes(evaluator.evaluate(context(days, START.plusDays(6), false))))
                .doesNotContain(RewardCode.STABILITY);
    }

    @Test
    void pushedToFinishUsesLastFiveActiveDaysAcrossCalendarRestDays() {
        LocalDate end = START.plusDays(7);
        List<UserDaySnapshot> days = List.of(
                missedDay(START),
                UserDaySnapshot.configuredRest(START.plusDays(1)),
                completedDay(START.plusDays(2), 2, true),
                completedDay(START.plusDays(3), 2, true),
                completedDay(START.plusDays(4), 2, true),
                completedDay(START.plusDays(5), 2, true),
                day(START.plusDays(6), 2, 1, true),
                UserDaySnapshot.configuredRest(end)
        );

        List<PersonalRewardEvaluator.RewardOccurrence> rewards = evaluator.evaluate(
                context(days, end, end, true));

        assertThat(rewards).filteredOn(reward -> reward.code() == RewardCode.PUSHED_TO_FINISH)
                .singleElement()
                .satisfies(reward -> {
                    assertThat(reward.earnedOn()).isEqualTo(end);
                    assertThat(reward.occurrenceKey()).isEqualTo("finish");
                });
    }

    @Test
    void pushedToFinishRequiresFinishedMarathonNinetyPercentAndNoMissedReports() {
        LocalDate end = START.plusDays(4);
        List<UserDaySnapshot> eightOfTen = List.of(
                completedDay(START, 2, true),
                completedDay(START.plusDays(1), 2, true),
                completedDay(START.plusDays(2), 2, true),
                day(START.plusDays(3), 2, 1, true),
                day(end, 2, 1, true)
        );
        List<UserDaySnapshot> withMiss = new ArrayList<>(sevenActiveDays(100, 100).subList(0, 5));
        withMiss.set(4, missedDay(end));

        assertThat(codes(evaluator.evaluate(context(eightOfTen, end, end, true))))
                .doesNotContain(RewardCode.PUSHED_TO_FINISH);
        assertThat(codes(evaluator.evaluate(context(withMiss, end, end, true))))
                .doesNotContain(RewardCode.PUSHED_TO_FINISH);
        assertThat(codes(evaluator.evaluate(context(fivePerfectDays(), end, end, false))))
                .doesNotContain(RewardCode.PUSHED_TO_FINISH);
    }

    @Test
    void fullDistanceRequiresAFinishedMarathonWithNoMissedActiveReports() {
        LocalDate end = START.plusDays(4);
        List<UserDaySnapshot> withMiss = new ArrayList<>(fivePerfectDays());
        withMiss.set(2, missedDay(START.plusDays(2)));

        assertThat(codes(evaluator.evaluate(context(fivePerfectDays(), end, end, true))))
                .contains(RewardCode.FULL_DISTANCE);
        assertThat(codes(evaluator.evaluate(context(withMiss, end, end, true))))
                .doesNotContain(RewardCode.FULL_DISTANCE);
        assertThat(codes(evaluator.evaluate(context(fivePerfectDays(), end, end, false))))
                .doesNotContain(RewardCode.FULL_DISTANCE);
    }

    private static List<UserDaySnapshot> sevenActiveDays(int completedGoals, int totalGoals) {
        List<UserDaySnapshot> days = new ArrayList<>();
        int completedRemaining = completedGoals;
        int totalRemaining = totalGoals;
        for (int dayIndex = 0; dayIndex < 7; dayIndex++) {
            int daysRemaining = 7 - dayIndex;
            int goalsToday = (int) Math.ceil((double) totalRemaining / daysRemaining);
            int completedToday = Math.min(goalsToday, completedRemaining);
            days.add(day(START.plusDays(dayIndex), goalsToday, completedToday, true));
            totalRemaining -= goalsToday;
            completedRemaining -= completedToday;
        }
        return List.copyOf(days);
    }

    private static List<UserDaySnapshot> fivePerfectDays() {
        return List.of(
                completedDay(START, 1, true),
                completedDay(START.plusDays(1), 1, true),
                completedDay(START.plusDays(2), 1, true),
                completedDay(START.plusDays(3), 1, true),
                completedDay(START.plusDays(4), 1, true)
        );
    }

    private static UserDaySnapshot completedDay(LocalDate date, int goalCount, boolean onTime) {
        return day(date, goalCount, goalCount, onTime);
    }

    private static UserDaySnapshot missedDay(LocalDate date) {
        return new UserDaySnapshot(
                date,
                false,
                false,
                UserDaySnapshot.ReportGoalSnapshot.absent(),
                List.of(new UserDaySnapshot.PersonalGoalSnapshot(1L, "Цель 1", false))
        );
    }

    private static UserDaySnapshot day(LocalDate date, int goalCount, int completedGoals, boolean onTime) {
        List<UserDaySnapshot.PersonalGoalSnapshot> goals = new ArrayList<>();
        for (int index = 0; index < goalCount; index++) {
            goals.add(new UserDaySnapshot.PersonalGoalSnapshot(
                    (long) index + 1,
                    "Цель " + (index + 1),
                    index < completedGoals
            ));
        }
        return new UserDaySnapshot(
                date,
                false,
                false,
                new UserDaySnapshot.ReportGoalSnapshot(true, onTime, LocalTime.of(onTime ? 10 : 23, 0)),
                goals
        );
    }

    private static RewardEvaluationContext context(
            List<UserDaySnapshot> days,
            LocalDate evaluationDate,
            boolean marathonFinished
    ) {
        return context(days, evaluationDate, START.plusDays(60), marathonFinished);
    }

    private static RewardEvaluationContext context(
            List<UserDaySnapshot> days,
            LocalDate evaluationDate,
            LocalDate marathonEndDate,
            boolean marathonFinished
    ) {
        return new RewardEvaluationContext(
                10L,
                20L,
                RewardCatalog.RULES_VERSION_1,
                evaluationDate,
                START,
                marathonEndDate,
                MOSCOW,
                marathonFinished,
                days
        );
    }

    private static List<RewardCode> codes(List<PersonalRewardEvaluator.RewardOccurrence> rewards) {
        return rewards.stream().map(PersonalRewardEvaluator.RewardOccurrence::code).toList();
    }
}
