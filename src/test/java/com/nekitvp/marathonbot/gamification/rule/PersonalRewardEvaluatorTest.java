package com.nekitvp.marathonbot.gamification.rule;

import com.nekitvp.marathonbot.gamification.model.RewardKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonalRewardEvaluatorTest {

    private static final LocalDate START = LocalDate.of(2026, 8, 1);
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private final RewardCatalog catalog = new RewardCatalog();
    private final PersonalRewardEvaluator evaluator = new PersonalRewardEvaluator(catalog);

    @ParameterizedTest
    @MethodSource("dailyGoalCases")
    void evaluatesMutuallyExclusiveDailyGoalMedals(
            List<Boolean> goalStatuses,
            Set<RewardCode> expectedCodes
    ) {
        List<PersonalRewardEvaluator.RewardOccurrence> rewards = evaluator.evaluate(context(List.of(
                day(START, true, LocalTime.NOON, goalStatuses)
        )));

        assertThat(codes(rewards)).containsAll(expectedCodes);
        assertThat(codes(rewards).stream()
                .filter(Set.of(RewardCode.STRONG_DAY, RewardCode.GREEN_DAY)::contains))
                .containsExactlyInAnyOrderElementsOf(expectedCodes);
    }

    static Stream<Arguments> dailyGoalCases() {
        return Stream.of(
                Arguments.of(List.of(), Set.of()),
                Arguments.of(List.of(false, false, false, false), Set.of()),
                Arguments.of(List.of(true, true, true, false), Set.of()),
                Arguments.of(List.of(true, true, true, true, false), Set.of(RewardCode.STRONG_DAY)),
                Arguments.of(List.of(true, true, true, true, true), Set.of(RewardCode.GREEN_DAY)),
                Arguments.of(Arrays.asList(true, true, true, true, null), Set.of(RewardCode.STRONG_DAY))
        );
    }

    @ParameterizedTest
    @MethodSource("timeMedalCases")
    void evaluatesMutuallyExclusiveTimeMedals(
            boolean onTime,
            LocalTime submittedAt,
            RewardCode expectedCode
    ) {
        List<PersonalRewardEvaluator.RewardOccurrence> rewards = evaluator.evaluate(context(List.of(
                day(START, onTime, submittedAt, List.of(false))
        )));

        assertThat(codes(rewards).stream()
                .filter(Set.of(RewardCode.EARLY_START, RewardCode.BEFORE_REMINDER)::contains))
                .containsExactly(expectedCode == null ? new RewardCode[0] : new RewardCode[]{expectedCode});
    }

    static Stream<Arguments> timeMedalCases() {
        return Stream.of(
                Arguments.of(true, LocalTime.of(11, 59), RewardCode.EARLY_START),
                Arguments.of(true, LocalTime.NOON, RewardCode.BEFORE_REMINDER),
                Arguments.of(true, LocalTime.of(21, 59), RewardCode.BEFORE_REMINDER),
                Arguments.of(true, LocalTime.of(22, 0), null),
                Arguments.of(false, LocalTime.of(11, 0), null),
                Arguments.of(true, null, null)
        );
    }

    @Test
    void awardsComebackAfterPreviousIncompleteActiveDayAcrossNeutralDays() {
        List<UserDaySnapshot> days = List.of(
                day(START, true, LocalTime.NOON, List.of(true, false)),
                UserDaySnapshot.configuredRest(START.plusDays(1)),
                UserDaySnapshot.personalDayOff(START.plusDays(2)),
                day(START.plusDays(3), true, LocalTime.of(10, 0), List.of(true, true))
        );

        List<PersonalRewardEvaluator.RewardOccurrence> rewards = evaluator.evaluate(context(days));

        assertThat(rewards).filteredOn(reward -> reward.code() == RewardCode.COMEBACK)
                .singleElement()
                .satisfies(reward -> {
                    assertThat(reward.earnedOn()).isEqualTo(START.plusDays(3));
                    assertThat(reward.occurrenceKey()).isEqualTo("date:2026-08-04");
                });
    }

    @Test
    void comebackRequiresCurrentOnTimeGreenDayAndAnIncompletePreviousActiveDay() {
        List<UserDaySnapshot> noRecovery = List.of(
                day(START, true, LocalTime.NOON, List.of(true, true)),
                day(START.plusDays(1), true, LocalTime.NOON, List.of(true, true))
        );
        List<UserDaySnapshot> lateRecovery = List.of(
                day(START, true, LocalTime.NOON, List.of(false, true)),
                day(START.plusDays(1), false, LocalTime.NOON, List.of(true, true))
        );

        assertThat(codes(evaluator.evaluate(context(noRecovery)))).doesNotContain(RewardCode.COMEBACK);
        assertThat(codes(evaluator.evaluate(context(lateRecovery)))).doesNotContain(RewardCode.COMEBACK);
    }

    @Test
    void lateSubmittedGreenReportKeepsCompletionMedalsButNotOnTimeRewards() {
        UserDaySnapshot late = new UserDaySnapshot(
                START, false, false,
                new UserDaySnapshot.ReportGoalSnapshot(false, false, true, LocalTime.NOON),
                List.of(
                        new UserDaySnapshot.PersonalGoalSnapshot(1L, "A", true),
                        new UserDaySnapshot.PersonalGoalSnapshot(2L, "B", true)));

        assertThat(codes(evaluator.evaluate(context(List.of(late)))))
                .contains(RewardCode.GREEN_DAY)
                .doesNotContain(RewardCode.FIRST_STEP, RewardCode.EARLY_START, RewardCode.BEFORE_REMINDER);
    }

    @ParameterizedTest
    @MethodSource("reportStreakCases")
    void awardsEveryReachedReportStreakTier(int activeDays, List<RewardCode> expected) {
        List<UserDaySnapshot> days = onTimeDays(activeDays, 1L, "Цель");

        assertThat(codes(evaluator.evaluate(context(days))))
                .filteredOn(code -> code.name().startsWith("REPORT_STREAK"))
                .containsExactlyElementsOf(expected);
    }

    static Stream<Arguments> reportStreakCases() {
        return Stream.of(
                Arguments.of(0, List.of()),
                Arguments.of(2, List.of()),
                Arguments.of(3, List.of(RewardCode.REPORT_STREAK_3)),
                Arguments.of(7, List.of(RewardCode.REPORT_STREAK_3, RewardCode.REPORT_STREAK_7)),
                Arguments.of(14, List.of(RewardCode.REPORT_STREAK_3, RewardCode.REPORT_STREAK_7,
                        RewardCode.REPORT_STREAK_14)),
                Arguments.of(21, List.of(RewardCode.REPORT_STREAK_3, RewardCode.REPORT_STREAK_7,
                        RewardCode.REPORT_STREAK_14, RewardCode.REPORT_STREAK_21))
        );
    }

    @Test
    void reportStreakCrossesConfiguredRestAndPersonalDayOff() {
        List<UserDaySnapshot> days = new ArrayList<>();
        days.addAll(onTimeDays(2, 1L, "Цель"));
        days.add(UserDaySnapshot.configuredRest(START.plusDays(2)));
        days.add(UserDaySnapshot.personalDayOff(START.plusDays(3)));
        days.add(dayWithGoal(START.plusDays(4), true, LocalTime.NOON, 1L, "Цель", true));

        assertThat(codes(evaluator.evaluate(context(days)))).contains(RewardCode.REPORT_STREAK_3);
    }

    @Test
    void unknownInternalCalendarGapDoesNotCreateSeriesOrComeback() {
        List<UserDaySnapshot> days = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            days.add(dayWithGoal(START.plusDays(index), true, LocalTime.NOON,
                    1L, "Цель", index < 5));
        }
        days.add(dayWithGoal(START.plusDays(7), true, LocalTime.NOON, 1L, "Цель", true));

        assertThat(codes(evaluator.evaluate(context(days))))
                .doesNotContain(
                        RewardCode.REPORT_STREAK_7,
                        RewardCode.GOAL_STREAK_7,
                        RewardCode.COMEBACK
                );
    }

    @ParameterizedTest
    @MethodSource("brokenReportDays")
    void lateAndMissingReportsBreakReportStreak(UserDaySnapshot brokenDay) {
        List<UserDaySnapshot> days = new ArrayList<>();
        days.addAll(onTimeDays(2, 1L, "Цель"));
        days.add(brokenDay);
        days.add(dayWithGoal(START.plusDays(3), true, LocalTime.NOON, 1L, "Цель", true));
        days.add(dayWithGoal(START.plusDays(4), true, LocalTime.NOON, 1L, "Цель", true));

        assertThat(codes(evaluator.evaluate(context(days)))).doesNotContain(RewardCode.REPORT_STREAK_3);
    }

    static Stream<UserDaySnapshot> brokenReportDays() {
        return Stream.of(
                dayWithGoal(START.plusDays(2), false, LocalTime.NOON, 1L, "Цель", true),
                missingDay(START.plusDays(2), 1L, "Цель")
        );
    }

    @Test
    void firstStepIsEarnedOnceFromTheFirstOnTimeReport() {
        List<UserDaySnapshot> days = List.of(
                missingDay(START, 1L, "Цель"),
                dayWithGoal(START.plusDays(1), true, LocalTime.NOON, 1L, "Цель", true),
                dayWithGoal(START.plusDays(2), true, LocalTime.NOON, 1L, "Цель", true)
        );

        assertThat(evaluator.evaluate(context(days)))
                .filteredOn(reward -> reward.code() == RewardCode.FIRST_STEP)
                .singleElement()
                .satisfies(reward -> {
                    assertThat(reward.earnedOn()).isEqualTo(START.plusDays(1));
                    assertThat(reward.occurrenceKey()).isEqualTo("tier:FIRST_STEP");
                });
    }

    @Test
    void awardsGoalStreakTiersPerGoalAcrossNeutralDays() {
        List<UserDaySnapshot> days = new ArrayList<>(onTimeDays(6, 42L, "Бег"));
        days.add(UserDaySnapshot.configuredRest(START.plusDays(6)));
        days.add(UserDaySnapshot.personalDayOff(START.plusDays(7)));
        for (int index = 8; index < 23; index++) {
            days.add(dayWithGoal(START.plusDays(index), true, LocalTime.NOON, 42L, "Бег", true));
        }

        assertThat(evaluator.evaluate(context(days)))
                .filteredOn(reward -> reward.code().name().startsWith("GOAL_STREAK"))
                .extracting(
                        PersonalRewardEvaluator.RewardOccurrence::code,
                        PersonalRewardEvaluator.RewardOccurrence::occurrenceKey,
                        PersonalRewardEvaluator.RewardOccurrence::goalIdSnapshot,
                        PersonalRewardEvaluator.RewardOccurrence::goalNameSnapshot
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(RewardCode.GOAL_STREAK_7, "goal:42", 42L, "Бег"),
                        org.assertj.core.groups.Tuple.tuple(RewardCode.GOAL_STREAK_14, "goal:42", 42L, "Бег"),
                        org.assertj.core.groups.Tuple.tuple(RewardCode.GOAL_STREAK_21, "goal:42", 42L, "Бег")
                );
    }

    @Test
    void goalFailureAndAbsenceBreakOnlyThatGoalsCurrentStreak() {
        List<UserDaySnapshot> days = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            days.add(dayWithGoal(START.plusDays(index), true, LocalTime.NOON, 7L, "Вода", true));
        }
        days.add(dayWithGoal(START.plusDays(6), true, LocalTime.NOON, 7L, "Вода", false));
        for (int index = 7; index < 13; index++) {
            days.add(dayWithGoal(START.plusDays(index), true, LocalTime.NOON, 7L, "Вода", true));
        }
        days.add(day(START.plusDays(13), true, LocalTime.NOON, List.of()));
        for (int index = 14; index < 20; index++) {
            days.add(dayWithGoal(START.plusDays(index), true, LocalTime.NOON, 7L, "Вода", true));
        }

        assertThat(codes(evaluator.evaluate(context(days))))
                .doesNotContain(RewardCode.GOAL_STREAK_7, RewardCode.GOAL_STREAK_14, RewardCode.GOAL_STREAK_21);
    }

    @Test
    void goalSnapshotsUseThresholdDayNameAndSurviveLaterRenameAndDeletion() {
        List<UserDaySnapshot> days = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            days.add(dayWithGoal(START.plusDays(index), true, LocalTime.NOON, 9L, "Старое имя", true));
        }
        for (int index = 7; index < 14; index++) {
            days.add(dayWithGoal(START.plusDays(index), true, LocalTime.NOON, 9L, "Новое имя", true));
        }
        days.add(day(START.plusDays(14), true, LocalTime.NOON, List.of()));

        assertThat(evaluator.evaluate(context(days)))
                .filteredOn(reward -> reward.goalIdSnapshot() != null)
                .extracting(
                        PersonalRewardEvaluator.RewardOccurrence::code,
                        PersonalRewardEvaluator.RewardOccurrence::goalNameSnapshot
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(RewardCode.GOAL_STREAK_7, "Старое имя"),
                        org.assertj.core.groups.Tuple.tuple(RewardCode.GOAL_STREAK_14, "Новое имя")
                );
    }

    @Test
    void sameGoalNameStillProducesSeparateGoalOccurrenceKeys() {
        List<UserDaySnapshot> days = IntStream.range(0, 7)
                .mapToObj(index -> new UserDaySnapshot(
                        START.plusDays(index),
                        false,
                        false,
                        new UserDaySnapshot.ReportGoalSnapshot(true, true, LocalTime.NOON),
                        List.of(
                                new UserDaySnapshot.PersonalGoalSnapshot(10L, "Зарядка", true),
                                new UserDaySnapshot.PersonalGoalSnapshot(11L, "Зарядка", true)
                        )
                ))
                .toList();

        assertThat(evaluator.evaluate(context(days)))
                .filteredOn(reward -> reward.code() == RewardCode.GOAL_STREAK_7)
                .extracting(PersonalRewardEvaluator.RewardOccurrence::occurrenceKey)
                .containsExactly("goal:10", "goal:11");
    }

    @ParameterizedTest
    @MethodSource("goalTotalCases")
    void awardsCompletedGoalTotalTiersAtExactBoundaries(int completed, List<RewardCode> expected) {
        List<UserDaySnapshot.PersonalGoalSnapshot> goals = IntStream.range(0, completed)
                .mapToObj(index -> new UserDaySnapshot.PersonalGoalSnapshot((long) index + 1, "Цель " + index, true))
                .toList();

        UserDaySnapshot snapshot = new UserDaySnapshot(
                START, false, false,
                new UserDaySnapshot.ReportGoalSnapshot(true, true, LocalTime.NOON), goals
        );

        assertThat(codes(evaluator.evaluate(context(List.of(snapshot)))))
                .filteredOn(code -> code.name().startsWith("GOALS_DONE"))
                .containsExactlyElementsOf(expected);
    }

    static Stream<Arguments> goalTotalCases() {
        return Stream.of(
                Arguments.of(0, List.of()),
                Arguments.of(24, List.of()),
                Arguments.of(25, List.of(RewardCode.GOALS_DONE_25)),
                Arguments.of(49, List.of(RewardCode.GOALS_DONE_25)),
                Arguments.of(50, List.of(RewardCode.GOALS_DONE_25, RewardCode.GOALS_DONE_50)),
                Arguments.of(100, List.of(RewardCode.GOALS_DONE_25, RewardCode.GOALS_DONE_50,
                        RewardCode.GOALS_DONE_100)),
                Arguments.of(200, List.of(RewardCode.GOALS_DONE_25, RewardCode.GOALS_DONE_50,
                        RewardCode.GOALS_DONE_100, RewardCode.GOALS_DONE_200))
        );
    }

    @ParameterizedTest
    @MethodSource("greenTotalCases")
    void awardsGreenDayTotalTiersAtExactBoundaries(int greenDays, List<RewardCode> expected) {
        List<UserDaySnapshot> days = IntStream.range(0, greenDays)
                .mapToObj(index -> dayWithGoal(START.plusDays(index), false, LocalTime.NOON,
                        1L, "Цель", true))
                .toList();

        assertThat(codes(evaluator.evaluate(context(days))))
                .filteredOn(code -> code.name().startsWith("GREEN_DAYS"))
                .containsExactlyElementsOf(expected);
    }

    static Stream<Arguments> greenTotalCases() {
        return Stream.of(
                Arguments.of(0, List.of()),
                Arguments.of(2, List.of()),
                Arguments.of(3, List.of(RewardCode.GREEN_DAYS_3)),
                Arguments.of(7, List.of(RewardCode.GREEN_DAYS_3, RewardCode.GREEN_DAYS_7)),
                Arguments.of(14, List.of(RewardCode.GREEN_DAYS_3, RewardCode.GREEN_DAYS_7,
                        RewardCode.GREEN_DAYS_14))
        );
    }

    @Test
    void ignoresRestDayDayOffEmptyAndOutOfWindowFacts() {
        List<UserDaySnapshot> days = List.of(
                dayWithGoal(START.minusDays(1), true, LocalTime.of(9, 0), 1L, "До старта", true),
                UserDaySnapshot.configuredRest(START),
                UserDaySnapshot.personalDayOff(START.plusDays(1)),
                day(START.plusDays(2), true, LocalTime.of(9, 0), List.of()),
                dayWithGoal(START.plusDays(4), true, LocalTime.of(9, 0), 2L, "После оценки", true)
        );
        RewardEvaluationContext context = context(days, START.plusDays(3));

        List<PersonalRewardEvaluator.RewardOccurrence> rewards = evaluator.evaluate(context);

        assertThat(codes(rewards)).containsExactly(RewardCode.EARLY_START, RewardCode.FIRST_STEP);
        assertThat(rewards).allSatisfy(reward -> {
            assertThat(reward.rulesVersion()).isEqualTo(RewardCatalog.RULES_VERSION_1);
            assertThat(reward.kind()).isEqualTo(catalog.definition(reward.code()).kind());
            assertThat(reward.earnedOn()).isBetween(START, START.plusDays(3));
        });
    }

    @Test
    void emptyContextReturnsNoRewardsAndUnsupportedRulesVersionIsRejected() {
        assertThat(evaluator.evaluate(context(List.of()))).isEmpty();

        RewardEvaluationContext unsupported = new RewardEvaluationContext(
                10L,
                20L,
                2,
                START,
                START,
                START.plusDays(10),
                MOSCOW,
                false,
                List.of()
        );
        assertThatThrownBy(() -> evaluator.evaluate(unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported personal reward rules version");
    }

    @Test
    void emitsOnlyTaskFourLaunchRewardsInStableOrder() {
        List<UserDaySnapshot> days = onTimeDays(21, 100L, "Цель");

        List<PersonalRewardEvaluator.RewardOccurrence> first = evaluator.evaluate(context(days));
        List<PersonalRewardEvaluator.RewardOccurrence> second = evaluator.evaluate(context(days));

        assertThat(second).isEqualTo(first);
        assertThat(codes(first)).doesNotContain(
                RewardCode.EARLY_BIRD_WEEK,
                RewardCode.STABILITY,
                RewardCode.FULL_DISTANCE,
                RewardCode.PUSHED_TO_FINISH,
                RewardCode.TEAM_DAY,
                RewardCode.ALL_ON_BOARD,
                RewardCode.TEAM_RHYTHM,
                RewardCode.PERFECT_TEAM_DAY,
                RewardCode.TEAM_WEEK,
                RewardCode.EXPEDITION_25,
                RewardCode.EXPEDITION_50,
                RewardCode.EXPEDITION_75,
                RewardCode.EXPEDITION_100
        );
        assertThat(first).allSatisfy(reward -> assertThat(reward.kind())
                .isIn(RewardKind.MEDAL, RewardKind.ACHIEVEMENT));
    }

    private static List<RewardCode> codes(List<PersonalRewardEvaluator.RewardOccurrence> rewards) {
        return rewards.stream().map(PersonalRewardEvaluator.RewardOccurrence::code).toList();
    }

    @Test
    void greenStreakTiersNeedConsecutiveGreenDaysAndResetOnAnAmberDay() {
        List<UserDaySnapshot> days = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            days.add(day(START.plusDays(index), true, LocalTime.NOON, List.of(true, true)));
        }
        days.add(day(START.plusDays(3), true, LocalTime.NOON, List.of(true, false)));
        days.add(day(START.plusDays(4), true, LocalTime.NOON, List.of(true, true)));

        List<RewardCode> earned = codes(evaluator.evaluate(context(days)));

        assertThat(earned).contains(RewardCode.GREEN_STREAK_3);
        assertThat(earned).doesNotContain(RewardCode.GREEN_STREAK_5, RewardCode.GREEN_STREAK_10);
    }

    @Test
    void restDaysDoNotBreakTheGreenStreak() {
        List<UserDaySnapshot> days = new ArrayList<>();
        days.add(day(START, true, LocalTime.NOON, List.of(true)));
        days.add(day(START.plusDays(1), true, LocalTime.NOON, List.of(true)));
        days.add(UserDaySnapshot.configuredRest(START.plusDays(2)));
        days.add(UserDaySnapshot.personalDayOff(START.plusDays(3)));
        days.add(day(START.plusDays(4), true, LocalTime.NOON, List.of(true)));

        assertThat(codes(evaluator.evaluate(context(days)))).contains(RewardCode.GREEN_STREAK_3);
    }

    @Test
    void greenStreakTierIsAwardedOnlyOncePerMarathon() {
        List<UserDaySnapshot> days = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            days.add(day(START.plusDays(index), true, LocalTime.NOON, List.of(true)));
        }

        List<PersonalRewardEvaluator.RewardOccurrence> rewards = evaluator.evaluate(context(days));

        assertThat(rewards.stream().filter(reward -> reward.code() == RewardCode.GREEN_STREAK_3)).hasSize(1);
        assertThat(codes(rewards)).contains(RewardCode.GREEN_STREAK_5);
    }

    private static RewardEvaluationContext context(List<UserDaySnapshot> days) {
        LocalDate evaluationDate = days.isEmpty()
                ? START
                : days.stream().map(UserDaySnapshot::activityDate).max(LocalDate::compareTo).orElseThrow();
        return context(days, evaluationDate);
    }

    private static RewardEvaluationContext context(List<UserDaySnapshot> days, LocalDate evaluationDate) {
        return new RewardEvaluationContext(
                10L,
                20L,
                RewardCatalog.RULES_VERSION_1,
                evaluationDate,
                START,
                START.plusDays(60),
                MOSCOW,
                false,
                days
        );
    }

    private static List<UserDaySnapshot> onTimeDays(int count, long goalId, String goalName) {
        return IntStream.range(0, count)
                .mapToObj(index -> dayWithGoal(
                        START.plusDays(index), true, LocalTime.NOON, goalId, goalName, true))
                .toList();
    }

    private static UserDaySnapshot day(
            LocalDate date,
            boolean onTime,
            LocalTime submittedAt,
            List<Boolean> goalStatuses
    ) {
        List<UserDaySnapshot.PersonalGoalSnapshot> goals = IntStream.range(0, goalStatuses.size())
                .mapToObj(index -> new UserDaySnapshot.PersonalGoalSnapshot(
                        (long) index + 1, "Цель " + index, goalStatuses.get(index)))
                .toList();
        return new UserDaySnapshot(
                date,
                false,
                false,
                new UserDaySnapshot.ReportGoalSnapshot(true, onTime, submittedAt),
                goals
        );
    }

    private static UserDaySnapshot dayWithGoal(
            LocalDate date,
            boolean onTime,
            LocalTime submittedAt,
            long goalId,
            String goalName,
            Boolean goalDone
    ) {
        return new UserDaySnapshot(
                date,
                false,
                false,
                new UserDaySnapshot.ReportGoalSnapshot(true, onTime, submittedAt),
                List.of(new UserDaySnapshot.PersonalGoalSnapshot(goalId, goalName, goalDone))
        );
    }

    private static UserDaySnapshot missingDay(LocalDate date, long goalId, String goalName) {
        return new UserDaySnapshot(
                date,
                false,
                false,
                new UserDaySnapshot.ReportGoalSnapshot(false, false, null),
                List.of(new UserDaySnapshot.PersonalGoalSnapshot(goalId, goalName, false))
        );
    }
}
