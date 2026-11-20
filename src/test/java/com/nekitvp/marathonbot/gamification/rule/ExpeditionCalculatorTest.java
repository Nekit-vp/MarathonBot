package com.nekitvp.marathonbot.gamification.rule;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExpeditionCalculatorTest {

    private final ExpeditionCalculator calculator = new ExpeditionCalculator();

    @Test
    void scoresZeroPartialEightyAndOneHundredPercentDays() {
        assertThat(calculator.calculateEnergy(activeDay(false, false, false, false)).energy()).isEqualTo(2);
        assertThat(calculator.calculateEnergy(activeDay(true, true, false, false, false, false)).energy()).isEqualTo(4);
        assertThat(calculator.calculateEnergy(activeDay(true, true, true, true, false)).energy()).isEqualTo(7);
        assertThat(calculator.calculateEnergy(activeDay(true, true, true, true, true)).energy()).isEqualTo(10);
    }

    @Test
    void lateGreenReportScoresEightButMissingReportScoresZero() {
        UserDaySnapshot late = day(false, false, true, false, true, true, true);
        UserDaySnapshot missing = day(false, false, null, false, null, null, null);

        assertThat(calculator.calculateEnergy(late).energy()).isEqualTo(8);
        assertThat(calculator.calculateEnergy(late).reportOnTime()).isFalse();
        assertThat(calculator.calculateEnergy(missing).energy()).isZero();
    }

    @Test
    void restAndPersonalDayOffAreNeutralAndExcludedFromTeamAverage() {
        ExpeditionCalculator.EnergyScore rest = calculator.calculateEnergy(UserDaySnapshot.configuredRest(DATE));
        ExpeditionCalculator.EnergyScore dayOff = calculator.calculateEnergy(UserDaySnapshot.personalDayOff(DATE));

        assertThat(rest.includedInTeamAverage()).isFalse();
        assertThat(dayOff.includedInTeamAverage()).isFalse();
        assertThat(rest.energy()).isZero();
        assertThat(dayOff.energy()).isZero();
    }

    @Test
    void reportWithNoPersonalGoalsGetsOnlyOnTimeEnergy() {
        UserDaySnapshot day = new UserDaySnapshot(
                DATE, false, false, new UserDaySnapshot.ReportGoalSnapshot(true, true, null), List.of());

        assertThat(calculator.calculateEnergy(day).energy()).isEqualTo(2);
    }

    @Test
    void countsOnlySnapshottedActiveCalendarDays() {
        assertThat(calculator.countActiveDays(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 9), true, true)).isEqualTo(5);
        assertThat(calculator.countActiveDays(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 9), false, true)).isEqualTo(7);
    }

    @Test
    void eightEnergyKeepsPaceAndTenEnergyCatchesUpAtOneHundredTwentyFivePercent() {
        assertThat(calculator.dailyDistance(10, new BigDecimal("8")))
                .isEqualByComparingTo("10");
        assertThat(calculator.dailyDistance(10, new BigDecimal("10")))
                .isEqualByComparingTo("12.5");
        assertThat(calculator.dailyDistance(10, new BigDecimal("80")))
                .isEqualByComparingTo("12.5");
    }

    @Test
    void routeUsesFullPrecisionAndFinishesExactlyAtOneHundred() {
        BigDecimal distance = calculator.routeDistance(
                3, List.of(new BigDecimal("8"), new BigDecimal("8"), new BigDecimal("8")));

        assertThat(distance).isEqualByComparingTo("100");
        assertThat(calculator.displayDistance(distance)).isEqualByComparingTo("100.00");
    }

    @Test
    void routeIsCappedAndPublishedDistanceNeverMovesBackwards() {
        BigDecimal overFinish = calculator.routeDistance(
                2, List.of(new BigDecimal("10"), new BigDecimal("10")));

        assertThat(overFinish).isEqualByComparingTo("100");
        assertThat(calculator.monotonicDistance(new BigDecimal("51.2345"), new BigDecimal("49.9999")))
                .isEqualByComparingTo("51.2345");
        assertThat(calculator.monotonicDistance(new BigDecimal("51.2345"), new BigDecimal("101")))
                .isEqualByComparingTo("100");
    }

    @Test
    void repeatedRouteCalculationIsDeterministicAndIncreasingWithEnergy() {
        List<BigDecimal> weak = List.of(new BigDecimal("4"), new BigDecimal("8"));
        List<BigDecimal> strong = List.of(new BigDecimal("4"), new BigDecimal("10"));

        BigDecimal first = calculator.routeDistance(10, weak);
        BigDecimal repeated = calculator.routeDistance(10, weak);
        BigDecimal increased = calculator.routeDistance(10, strong);

        assertThat(repeated).isEqualByComparingTo(first);
        assertThat(increased).isGreaterThan(first);
    }

    private static final LocalDate DATE = LocalDate.of(2026, 8, 1);

    private static UserDaySnapshot activeDay(Boolean... goals) {
        return day(false, false, true, true, goals);
    }

    private static UserDaySnapshot day(
            boolean rest,
            boolean personalDayOff,
            Boolean reportDone,
            boolean reportOnTime,
            Boolean... goals
    ) {
        List<UserDaySnapshot.PersonalGoalSnapshot> personalGoals = java.util.stream.IntStream.range(0, goals.length)
                .mapToObj(index -> new UserDaySnapshot.PersonalGoalSnapshot(
                        (long) index + 1, "Goal " + index, goals[index]))
                .toList();
        return new UserDaySnapshot(
                DATE,
                rest,
                personalDayOff,
                new UserDaySnapshot.ReportGoalSnapshot(reportDone, reportOnTime, null),
                personalGoals
        );
    }
}
