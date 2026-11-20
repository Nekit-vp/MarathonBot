package com.nekitvp.marathonbot.gamification.rule;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure evaluator for repeatable team medals, team achievements, and route checkpoints. */
@Component
public final class TeamRewardEvaluator {

    private static final int TEAM_DAY_ENERGY = 8;
    private static final int TEAM_RHYTHM_PERCENT = 90;
    private static final int TEAM_RHYTHM_DAYS = 3;
    private static final int PERFECT_TEAM_PERCENT = 95;
    private static final int TEAM_WEEK_PERCENT = 85;
    private static final int TEAM_WEEK_DAYS = 7;
    private static final BigDecimal ROUTE_FINISH = new BigDecimal("100");
    private static final MathContext CALCULATION_CONTEXT = MathContext.DECIMAL128;

    private static final List<Checkpoint> CHECKPOINTS = List.of(
            new Checkpoint(RewardCode.EXPEDITION_25, 25),
            new Checkpoint(RewardCode.EXPEDITION_50, 50),
            new Checkpoint(RewardCode.EXPEDITION_75, 75),
            new Checkpoint(RewardCode.EXPEDITION_100, 100)
    );

    public List<TeamRewardOccurrence> evaluate(TeamEvaluationContext context) {
        Objects.requireNonNull(context, "context");
        if (context.rulesVersion() != RewardCatalog.RULES_VERSION_1) {
            throw new IllegalArgumentException("Unsupported team reward rules version: " + context.rulesVersion());
        }

        List<TeamDaySnapshot> activeDays = context.days().stream()
                .filter(day -> !day.activityDate().isAfter(context.evaluationDate()))
                .filter(day -> !day.configuredRestDay())
                .filter(day -> day.eligibleParticipants().size() > 0)
                .toList();
        List<TeamRewardOccurrence> rewards = new ArrayList<>();
        Set<RewardCode> emittedUniqueRewards = EnumSet.noneOf(RewardCode.class);
        int rhythmDays = 0;

        for (int index = 0; index < activeDays.size(); index++) {
            TeamDaySnapshot day = activeDays.get(index);
            List<Long> recipients = day.eligibleUserIds();
            if (day.averageEnergy().compareTo(BigDecimal.valueOf(TEAM_DAY_ENERGY)) >= 0) {
                rewards.add(new TeamRewardOccurrence(RewardCode.TEAM_DAY, day.activityDate(), recipients));
            }
            if (day.everyoneReportedOnTime()) {
                addUnique(RewardCode.ALL_ON_BOARD, day, recipients, emittedUniqueRewards, rewards);
            }
            if (day.everyoneReportedOnTime() && day.reachesGoalPercentage(PERFECT_TEAM_PERCENT)) {
                addUnique(RewardCode.PERFECT_TEAM_DAY, day, recipients, emittedUniqueRewards, rewards);
            }

            rhythmDays = day.reachesOnTimePercentage(TEAM_RHYTHM_PERCENT) ? rhythmDays + 1 : 0;
            if (rhythmDays >= TEAM_RHYTHM_DAYS) {
                addUnique(RewardCode.TEAM_RHYTHM, day, recipients, emittedUniqueRewards, rewards);
            }

            if (index + 1 >= TEAM_WEEK_DAYS) {
                List<TeamDaySnapshot> week = activeDays.subList(index + 1 - TEAM_WEEK_DAYS, index + 1);
                if (week.stream().allMatch(TeamDaySnapshot::everyoneSubmittedReport)
                        && reachesGoalPercentage(week, TEAM_WEEK_PERCENT)) {
                    addUnique(RewardCode.TEAM_WEEK, day, recipients, emittedUniqueRewards, rewards);
                }
            }
        }

        addCrossedCheckpoints(context, rewards);
        return List.copyOf(rewards);
    }

    private static void addUnique(
            RewardCode code,
            TeamDaySnapshot day,
            List<Long> recipients,
            Set<RewardCode> emitted,
            List<TeamRewardOccurrence> rewards
    ) {
        if (emitted.add(code)) {
            rewards.add(new TeamRewardOccurrence(code, day.activityDate(), recipients));
        }
    }

    private static boolean reachesGoalPercentage(List<TeamDaySnapshot> days, int requiredPercent) {
        long totalGoals = days.stream().mapToLong(TeamDaySnapshot::totalGoalCount).sum();
        if (totalGoals == 0) {
            return false;
        }
        long completedGoals = days.stream().mapToLong(TeamDaySnapshot::completedGoalCount).sum();
        return completedGoals * 100 >= totalGoals * requiredPercent;
    }

    private static void addCrossedCheckpoints(
            TeamEvaluationContext context,
            List<TeamRewardOccurrence> rewards
    ) {
        BigDecimal previous = cap(context.previousPublishedDistance());
        BigDecimal current = previous.max(cap(context.currentCalculatedDistance()));
        List<Long> recipients = context.days().stream()
                .filter(day -> day.activityDate().equals(context.evaluationDate()))
                .findFirst()
                .map(TeamDaySnapshot::rosterUserIds)
                .orElse(List.of());
        if (recipients.isEmpty()) {
            return;
        }

        for (Checkpoint checkpoint : CHECKPOINTS) {
            BigDecimal threshold = BigDecimal.valueOf(checkpoint.threshold());
            if (previous.compareTo(threshold) < 0 && current.compareTo(threshold) >= 0) {
                rewards.add(new TeamRewardOccurrence(checkpoint.code(), context.evaluationDate(), recipients));
            }
        }
    }

    private static BigDecimal cap(BigDecimal value) {
        return value.min(ROUTE_FINISH);
    }

    private record Checkpoint(RewardCode code, int threshold) {
    }

    public record TeamEvaluationContext(
            int rulesVersion,
            LocalDate evaluationDate,
            List<TeamDaySnapshot> days,
            BigDecimal previousPublishedDistance,
            BigDecimal currentCalculatedDistance
    ) {
        public TeamEvaluationContext {
            if (rulesVersion <= 0) {
                throw new IllegalArgumentException("rulesVersion must be positive");
            }
            Objects.requireNonNull(evaluationDate, "evaluationDate");
            previousPublishedDistance = requireDistance(previousPublishedDistance, "previousPublishedDistance");
            currentCalculatedDistance = requireDistance(currentCalculatedDistance, "currentCalculatedDistance");
            List<TeamDaySnapshot> orderedDays = Objects.requireNonNull(days, "days").stream()
                    .sorted(Comparator.comparing(TeamDaySnapshot::activityDate))
                    .toList();
            Set<LocalDate> uniqueDates = new HashSet<>();
            for (TeamDaySnapshot day : orderedDays) {
                if (!uniqueDates.add(day.activityDate())) {
                    throw new IllegalArgumentException("Duplicate team day snapshot: " + day.activityDate());
                }
            }
            days = List.copyOf(orderedDays);
        }

        private static BigDecimal requireDistance(BigDecimal distance, String name) {
            Objects.requireNonNull(distance, name);
            if (distance.signum() < 0) {
                throw new IllegalArgumentException(name + " cannot be negative");
            }
            return distance;
        }
    }

    public record TeamDaySnapshot(
            LocalDate activityDate,
            boolean configuredRestDay,
            List<ParticipantDaySnapshot> participants
    ) {
        public TeamDaySnapshot {
            Objects.requireNonNull(activityDate, "activityDate");
            List<ParticipantDaySnapshot> orderedParticipants = Objects.requireNonNull(participants, "participants").stream()
                    .sorted(Comparator.comparing(ParticipantDaySnapshot::userId))
                    .toList();
            Set<Long> uniqueUserIds = new HashSet<>();
            for (ParticipantDaySnapshot participant : orderedParticipants) {
                if (!uniqueUserIds.add(participant.userId())) {
                    throw new IllegalArgumentException("Duplicate participant snapshot: " + participant.userId());
                }
            }
            participants = List.copyOf(orderedParticipants);
        }

        public static TeamDaySnapshot configuredRest(LocalDate date) {
            return new TeamDaySnapshot(date, true, List.of());
        }

        public List<ParticipantDaySnapshot> eligibleParticipants() {
            return participants.stream().filter(participant -> !participant.personalDayOff()).toList();
        }

        public List<Long> eligibleUserIds() {
            return eligibleParticipants().stream().map(ParticipantDaySnapshot::userId).toList();
        }

        public List<Long> rosterUserIds() {
            return participants.stream().map(ParticipantDaySnapshot::userId).toList();
        }

        public BigDecimal averageEnergy() {
            List<ParticipantDaySnapshot> eligible = eligibleParticipants();
            if (eligible.isEmpty()) {
                return BigDecimal.ZERO;
            }
            long totalEnergy = eligible.stream().mapToLong(ParticipantDaySnapshot::energy).sum();
            return BigDecimal.valueOf(totalEnergy)
                    .divide(BigDecimal.valueOf(eligible.size()), CALCULATION_CONTEXT)
                    .stripTrailingZeros();
        }

        public boolean everyoneReportedOnTime() {
            List<ParticipantDaySnapshot> eligible = eligibleParticipants();
            return !eligible.isEmpty() && eligible.stream().allMatch(ParticipantDaySnapshot::reportOnTime);
        }

        public boolean everyoneSubmittedReport() {
            List<ParticipantDaySnapshot> eligible = eligibleParticipants();
            return !eligible.isEmpty() && eligible.stream().allMatch(ParticipantDaySnapshot::reportSubmitted);
        }

        public boolean reachesOnTimePercentage(int requiredPercent) {
            List<ParticipantDaySnapshot> eligible = eligibleParticipants();
            if (eligible.isEmpty()) {
                return false;
            }
            long onTimeCount = eligible.stream().filter(ParticipantDaySnapshot::reportOnTime).count();
            return onTimeCount * 100 >= (long) eligible.size() * requiredPercent;
        }

        public boolean reachesGoalPercentage(int requiredPercent) {
            long totalGoals = totalGoalCount();
            return totalGoals > 0 && completedGoalCount() * 100 >= totalGoals * requiredPercent;
        }

        public long totalGoalCount() {
            return eligibleParticipants().stream().mapToLong(ParticipantDaySnapshot::personalGoalCount).sum();
        }

        public long completedGoalCount() {
            return eligibleParticipants().stream().mapToLong(ParticipantDaySnapshot::completedGoalCount).sum();
        }
    }

    public record ParticipantDaySnapshot(
            Long userId,
            boolean reportSubmitted,
            boolean reportOnTime,
            boolean personalDayOff,
            int personalGoalCount,
            int completedGoalCount,
            int energy
    ) {
        public ParticipantDaySnapshot {
            if (userId == null || userId <= 0) {
                throw new IllegalArgumentException("userId must be positive");
            }
            if (reportOnTime && !reportSubmitted) {
                throw new IllegalArgumentException("An on-time report must be submitted");
            }
            if (personalGoalCount < 0 || completedGoalCount < 0 || completedGoalCount > personalGoalCount) {
                throw new IllegalArgumentException("Invalid personal-goal counts");
            }
            if (energy < 0 || energy > ExpeditionCalculator.MAX_DAILY_ENERGY) {
                throw new IllegalArgumentException("Energy must be between 0 and "
                        + ExpeditionCalculator.MAX_DAILY_ENERGY);
            }
            if (personalDayOff && (reportSubmitted || reportOnTime || personalGoalCount != 0 || energy != 0)) {
                throw new IllegalArgumentException("A personal day off is neutral");
            }
        }

        public ExpeditionCalculator.EnergyScore energyScore() {
            return new ExpeditionCalculator.EnergyScore(
                    !personalDayOff,
                    reportOnTime,
                    personalGoalCount,
                    completedGoalCount,
                    energy
            );
        }
    }

    public record TeamRewardOccurrence(
            RewardCode code,
            LocalDate earnedOn,
            List<Long> recipientUserIds
    ) {
        public TeamRewardOccurrence {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(earnedOn, "earnedOn");
            recipientUserIds = List.copyOf(Objects.requireNonNull(recipientUserIds, "recipientUserIds"));
            if (recipientUserIds.isEmpty()) {
                throw new IllegalArgumentException("A team reward must have recipients");
            }
        }
    }
}
