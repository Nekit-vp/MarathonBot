package com.nekitvp.marathonbot.gamification.rule;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TeamRewardEvaluatorTest {

    private final ExpeditionCalculator expeditionCalculator = new ExpeditionCalculator();
    private final TeamRewardEvaluator evaluator = new TeamRewardEvaluator();

    @Test
    void teamAverageIncludesMissingAsZeroAndExcludesPersonalDayOff() {
        TeamRewardEvaluator.TeamDaySnapshot day = day(0,
                participant(1, true, true, false, 5, 5, 10),
                participant(2, false, false, false, 5, 0, 0),
                participant(3, false, false, true, 0, 0, 0));

        assertThat(expeditionCalculator.teamAverageEnergy(day.participants().stream()
                .map(TeamRewardEvaluator.ParticipantDaySnapshot::energyScore)
                .toList())).isEqualByComparingTo("5");
    }

    @Test
    void teamDayGoesOnlyToEligibleParticipantsWhenAverageReachesEight() {
        TeamRewardEvaluator.TeamDaySnapshot day = day(0,
                participant(1, true, true, false, 5, 5, 10),
                participant(2, true, true, false, 5, 4, 6),
                participant(3, false, false, true, 0, 0, 0));

        TeamRewardEvaluator.TeamRewardOccurrence reward = onlyReward(day, RewardCode.TEAM_DAY);

        assertThat(reward.recipientUserIds()).containsExactly(1L, 2L);
    }

    @Test
    void allOnBoardRequiresEveryEligibleParticipantToReportOnTime() {
        TeamRewardEvaluator.TeamDaySnapshot day = day(0,
                participant(1, true, true, false, 2, 2, 10),
                participant(2, true, true, false, 2, 2, 10));

        assertThat(codes(evaluate(List.of(day)))).contains(RewardCode.ALL_ON_BOARD);
        assertThat(codes(evaluate(List.of(day(0,
                participant(1, true, true, false, 2, 2, 10),
                participant(2, true, false, false, 2, 2, 8)))))).doesNotContain(RewardCode.ALL_ON_BOARD);
    }

    @Test
    void teamRhythmRequiresNinetyPercentOnTimeForThreeSequentialActiveDays() {
        List<TeamRewardEvaluator.TeamDaySnapshot> days = List.of(
                tenPersonDay(0, 9),
                TeamRewardEvaluator.TeamDaySnapshot.configuredRest(DATE.plusDays(1)),
                tenPersonDay(2, 9),
                tenPersonDay(3, 9));

        TeamRewardEvaluator.TeamRewardOccurrence rhythm = evaluate(days).stream()
                .filter(reward -> reward.code() == RewardCode.TEAM_RHYTHM)
                .findFirst().orElseThrow();

        assertThat(rhythm.earnedOn()).isEqualTo(DATE.plusDays(3));
    }

    @Test
    void weakActiveDayBreaksTeamRhythm() {
        List<TeamRewardEvaluator.TeamDaySnapshot> days = List.of(
                tenPersonDay(0, 9), tenPersonDay(1, 8), tenPersonDay(2, 9), tenPersonDay(3, 9));

        assertThat(codes(evaluate(days))).doesNotContain(RewardCode.TEAM_RHYTHM);
    }

    @Test
    void perfectTeamDayRequiresEveryoneOnTimeAndNinetyFivePercentGoals() {
        TeamRewardEvaluator.TeamDaySnapshot perfect = day(0,
                participant(1, true, true, false, 10, 10, 10),
                participant(2, true, true, false, 10, 9, 9));
        TeamRewardEvaluator.TeamDaySnapshot below = day(1,
                participant(1, true, true, false, 10, 9, 9),
                participant(2, true, true, false, 10, 9, 9));

        assertThat(codes(evaluate(List.of(perfect)))).contains(RewardCode.PERFECT_TEAM_DAY);
        assertThat(codes(evaluate(List.of(below)))).doesNotContain(RewardCode.PERFECT_TEAM_DAY);
    }

    @Test
    void teamWeekAllowsLateReportsButRequiresSevenDaysWithoutMissingAndEightyFivePercentGoals() {
        List<TeamRewardEvaluator.TeamDaySnapshot> days = new ArrayList<>();
        for (int offset = 0; offset < 7; offset++) {
            boolean onTime = offset != 3;
            days.add(day(offset, participant(1, true, onTime, false, 20, 17, onTime ? 7 : 5)));
        }

        TeamRewardEvaluator.TeamRewardOccurrence week = evaluate(days).stream()
                .filter(reward -> reward.code() == RewardCode.TEAM_WEEK)
                .findFirst().orElseThrow();

        assertThat(week.earnedOn()).isEqualTo(DATE.plusDays(6));
    }

    @Test
    void missingReportPreventsTeamWeek() {
        List<TeamRewardEvaluator.TeamDaySnapshot> days = new ArrayList<>();
        for (int offset = 0; offset < 7; offset++) {
            days.add(day(offset, participant(1, offset != 4, offset != 4, false, 20, 17, offset == 4 ? 0 : 7)));
        }

        assertThat(codes(evaluate(days))).doesNotContain(RewardCode.TEAM_WEEK);
    }

    @Test
    void emitsAllFourCrossedCheckpointsExactlyOnceAndInOrder() {
        TeamRewardEvaluator.TeamDaySnapshot day = day(0,
                participant(2, true, true, false, 1, 1, 10),
                participant(1, true, true, false, 1, 1, 10));

        List<TeamRewardEvaluator.TeamRewardOccurrence> checkpoints = evaluate(
                List.of(day), BigDecimal.ZERO, new BigDecimal("100")).stream()
                .filter(reward -> reward.code().name().startsWith("EXPEDITION_"))
                .toList();

        assertThat(codes(checkpoints)).containsExactly(
                RewardCode.EXPEDITION_25,
                RewardCode.EXPEDITION_50,
                RewardCode.EXPEDITION_75,
                RewardCode.EXPEDITION_100);
        assertThat(checkpoints).allSatisfy(reward -> assertThat(reward.recipientUserIds()).containsExactly(1L, 2L));
    }

    @Test
    void checkpointResultsAreMonotonicAcrossRecalculationAndDeterministic() {
        TeamRewardEvaluator.TeamDaySnapshot day = day(0,
                participant(1, true, true, false, 1, 1, 10));

        List<TeamRewardEvaluator.TeamRewardOccurrence> first = evaluate(
                List.of(day), new BigDecimal("25"), new BigDecimal("20"));
        List<TeamRewardEvaluator.TeamRewardOccurrence> repeated = evaluate(
                List.of(day), new BigDecimal("25"), new BigDecimal("20"));

        assertThat(first).isEqualTo(repeated);
        assertThat(codes(first)).doesNotContain(RewardCode.EXPEDITION_25);
    }

    private TeamRewardEvaluator.TeamRewardOccurrence onlyReward(
            TeamRewardEvaluator.TeamDaySnapshot day,
            RewardCode code
    ) {
        return evaluate(List.of(day)).stream().filter(reward -> reward.code() == code).findFirst().orElseThrow();
    }

    private List<TeamRewardEvaluator.TeamRewardOccurrence> evaluate(
            List<TeamRewardEvaluator.TeamDaySnapshot> days
    ) {
        return evaluate(days, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private List<TeamRewardEvaluator.TeamRewardOccurrence> evaluate(
            List<TeamRewardEvaluator.TeamDaySnapshot> days,
            BigDecimal previousDistance,
            BigDecimal currentDistance
    ) {
        LocalDate evaluationDate = days.stream()
                .map(TeamRewardEvaluator.TeamDaySnapshot::activityDate)
                .max(LocalDate::compareTo)
                .orElse(DATE);
        return evaluator.evaluate(new TeamRewardEvaluator.TeamEvaluationContext(
                RewardCatalog.RULES_VERSION_1,
                evaluationDate,
                days,
                previousDistance,
                currentDistance));
    }

    private static List<RewardCode> codes(List<TeamRewardEvaluator.TeamRewardOccurrence> rewards) {
        return rewards.stream().map(TeamRewardEvaluator.TeamRewardOccurrence::code).toList();
    }

    private static TeamRewardEvaluator.TeamDaySnapshot tenPersonDay(int offset, int onTimeCount) {
        List<TeamRewardEvaluator.ParticipantDaySnapshot> participants = java.util.stream.LongStream.rangeClosed(1, 10)
                .mapToObj(userId -> participant(
                        userId, true, userId <= onTimeCount, false, 2, 2, userId <= onTimeCount ? 10 : 8))
                .toList();
        return new TeamRewardEvaluator.TeamDaySnapshot(DATE.plusDays(offset), false, participants);
    }

    private static TeamRewardEvaluator.TeamDaySnapshot day(
            int offset,
            TeamRewardEvaluator.ParticipantDaySnapshot... participants
    ) {
        return new TeamRewardEvaluator.TeamDaySnapshot(DATE.plusDays(offset), false, List.of(participants));
    }

    private static TeamRewardEvaluator.ParticipantDaySnapshot participant(
            long userId,
            boolean reportSubmitted,
            boolean reportOnTime,
            boolean personalDayOff,
            int totalGoals,
            int completedGoals,
            int energy
    ) {
        return new TeamRewardEvaluator.ParticipantDaySnapshot(
                userId, reportSubmitted, reportOnTime, personalDayOff, totalGoals, completedGoals, energy);
    }

    private static final LocalDate DATE = LocalDate.of(2026, 8, 3);
}
