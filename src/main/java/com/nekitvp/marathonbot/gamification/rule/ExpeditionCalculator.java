package com.nekitvp.marathonbot.gamification.rule;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Pure calculator for participant energy and expedition route distance. */
@Component
public final class ExpeditionCalculator {

    public static final int MAX_DAILY_ENERGY = 10;
    private static final BigDecimal ROUTE_FINISH = new BigDecimal("100");
    private static final BigDecimal TARGET_ENERGY = new BigDecimal("8");
    private static final BigDecimal MAX_DAILY_FACTOR = new BigDecimal("1.25");
    private static final MathContext CALCULATION_CONTEXT = MathContext.DECIMAL128;

    public EnergyScore calculateEnergy(UserDaySnapshot day) {
        Objects.requireNonNull(day, "day");
        if (day.configuredRestDay() || day.personalDayOff()) {
            return new EnergyScore(false, false, day.personalGoals().size(), 0, 0);
        }

        int totalGoals = day.personalGoals().size();
        int completedGoals = (int) day.personalGoals().stream()
                .filter(goal -> Boolean.TRUE.equals(goal.done()))
                .count();
        boolean reportSubmitted = day.reportSubmitted();
        if (!reportSubmitted) {
            return new EnergyScore(true, false, totalGoals, completedGoals, 0);
        }

        boolean reportOnTime = day.reportGoal().onTime();
        int onTimeEnergy = reportOnTime ? 2 : 0;
        int goalEnergy = totalGoals == 0
                ? 0
                : BigDecimal.valueOf(6L * completedGoals)
                        .divide(BigDecimal.valueOf(totalGoals), 0, RoundingMode.HALF_UP)
                        .intValueExact();
        int greenBonus = totalGoals > 0 && completedGoals == totalGoals ? 2 : 0;
        int totalEnergy = Math.min(MAX_DAILY_ENERGY, onTimeEnergy + goalEnergy + greenBonus);
        return new EnergyScore(true, reportOnTime, totalGoals, completedGoals, totalEnergy);
    }

    /** Average includes explicit zero-energy misses and excludes neutral days off. */
    public BigDecimal teamAverageEnergy(List<EnergyScore> scores) {
        List<EnergyScore> eligible = Objects.requireNonNull(scores, "scores").stream()
                .filter(EnergyScore::includedInTeamAverage)
                .toList();
        if (eligible.isEmpty()) {
            return BigDecimal.ZERO;
        }
        long totalEnergy = eligible.stream().mapToLong(EnergyScore::energy).sum();
        return BigDecimal.valueOf(totalEnergy)
                .divide(BigDecimal.valueOf(eligible.size()), CALCULATION_CONTEXT)
                .stripTrailingZeros();
    }

    /** Counts the immutable activation-time route denominator, inclusive of both dates. */
    public int countActiveDays(
            LocalDate routeStartDate,
            LocalDate routeEndDate,
            boolean restOnSaturday,
            boolean restOnSunday
    ) {
        Objects.requireNonNull(routeStartDate, "routeStartDate");
        Objects.requireNonNull(routeEndDate, "routeEndDate");
        if (routeEndDate.isBefore(routeStartDate)) {
            throw new IllegalArgumentException("Route end date cannot precede start date");
        }

        int activeDays = 0;
        for (LocalDate date = routeStartDate; !date.isAfter(routeEndDate); date = date.plusDays(1)) {
            DayOfWeek dayOfWeek = date.getDayOfWeek();
            if ((restOnSaturday && dayOfWeek == DayOfWeek.SATURDAY)
                    || (restOnSunday && dayOfWeek == DayOfWeek.SUNDAY)) {
                continue;
            }
            activeDays++;
        }
        return activeDays;
    }

    public BigDecimal dailyDistance(int plannedActiveDays, BigDecimal teamAverageEnergy) {
        validatePlannedActiveDays(plannedActiveDays);
        BigDecimal factor = dailyFactor(teamAverageEnergy);
        return ROUTE_FINISH
                .multiply(factor, CALCULATION_CONTEXT)
                .divide(BigDecimal.valueOf(plannedActiveDays), CALCULATION_CONTEXT)
                .stripTrailingZeros();
    }

    /** Recalculates the route from precise daily averages and rounds only at presentation time. */
    public BigDecimal routeDistance(int plannedActiveDays, List<BigDecimal> dailyTeamAverageEnergies) {
        validatePlannedActiveDays(plannedActiveDays);
        BigDecimal factorSum = Objects.requireNonNull(dailyTeamAverageEnergies, "dailyTeamAverageEnergies").stream()
                .map(this::dailyFactor)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, CALCULATION_CONTEXT));
        BigDecimal calculated = ROUTE_FINISH
                .multiply(factorSum, CALCULATION_CONTEXT)
                .divide(BigDecimal.valueOf(plannedActiveDays), CALCULATION_CONTEXT);
        return cap(calculated).stripTrailingZeros();
    }

    /** Keeps already credited route progress when corrected source data would lower it. */
    public BigDecimal monotonicDistance(BigDecimal creditedDistance, BigDecimal recalculatedDistance) {
        BigDecimal credited = cap(requireNonNegative(creditedDistance, "creditedDistance"));
        BigDecimal recalculated = cap(requireNonNegative(recalculatedDistance, "recalculatedDistance"));
        return credited.max(recalculated).stripTrailingZeros();
    }

    public BigDecimal displayDistance(BigDecimal preciseDistance) {
        return cap(requireNonNegative(preciseDistance, "preciseDistance")).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal dailyFactor(BigDecimal teamAverageEnergy) {
        BigDecimal average = requireNonNegative(teamAverageEnergy, "teamAverageEnergy");
        return average.divide(TARGET_ENERGY, CALCULATION_CONTEXT).min(MAX_DAILY_FACTOR);
    }

    private static void validatePlannedActiveDays(int plannedActiveDays) {
        if (plannedActiveDays <= 0) {
            throw new IllegalArgumentException("plannedActiveDays must be positive");
        }
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
        return value;
    }

    private static BigDecimal cap(BigDecimal distance) {
        return distance.min(ROUTE_FINISH);
    }

    public record EnergyScore(
            boolean includedInTeamAverage,
            boolean reportOnTime,
            int personalGoalCount,
            int completedGoalCount,
            int energy
    ) {
        public EnergyScore {
            if (personalGoalCount < 0 || completedGoalCount < 0 || completedGoalCount > personalGoalCount) {
                throw new IllegalArgumentException("Invalid personal-goal counts");
            }
            if (energy < 0 || energy > MAX_DAILY_ENERGY) {
                throw new IllegalArgumentException("Energy must be between 0 and " + MAX_DAILY_ENERGY);
            }
            if (!includedInTeamAverage && (reportOnTime || energy != 0)) {
                throw new IllegalArgumentException("Neutral days cannot contribute report or energy facts");
            }
        }
    }
}
