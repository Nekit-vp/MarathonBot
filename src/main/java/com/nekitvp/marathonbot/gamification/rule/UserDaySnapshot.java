package com.nekitvp.marathonbot.gamification.rule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/** Immutable facts materialized from one participant's histories for one Moscow activity date. */
public record UserDaySnapshot(
        LocalDate activityDate,
        boolean configuredRestDay,
        boolean personalDayOff,
        ReportGoalSnapshot reportGoal,
        List<PersonalGoalSnapshot> personalGoals
) {
    public UserDaySnapshot {
        Objects.requireNonNull(activityDate, "activityDate");
        Objects.requireNonNull(reportGoal, "reportGoal");
        personalGoals = List.copyOf(Objects.requireNonNull(personalGoals, "personalGoals"));
        if (configuredRestDay && personalDayOff) {
            throw new IllegalArgumentException("A day cannot be both configured rest and personal day off");
        }
        if (personalDayOff && (!Boolean.TRUE.equals(reportGoal.done()) || !personalGoals.isEmpty())) {
            throw new IllegalArgumentException("A personal day off contains only a completed report goal");
        }
    }

    public static UserDaySnapshot configuredRest(LocalDate date) {
        return new UserDaySnapshot(date, true, false, ReportGoalSnapshot.absent(), List.of());
    }

    public static UserDaySnapshot personalDayOff(LocalDate date) {
        return new UserDaySnapshot(date, false, true, new ReportGoalSnapshot(true, false, null), List.of());
    }

    /** A late report keeps the mandatory report goal false but has explicit personal-goal answers. */
    public boolean reportSubmitted() {
        return reportGoal.submitted();
    }

    public record ReportGoalSnapshot(Boolean done, boolean onTime, boolean submitted, LocalTime submittedAt) {
        public ReportGoalSnapshot(Boolean done, boolean onTime, LocalTime submittedAt) {
            this(done, onTime, Boolean.TRUE.equals(done) || submittedAt != null, submittedAt);
        }

        public ReportGoalSnapshot {
            if (onTime && !Boolean.TRUE.equals(done)) {
                throw new IllegalArgumentException("An on-time report goal must be completed");
            }
            if (onTime && !submitted) {
                throw new IllegalArgumentException("An on-time report must be submitted");
            }
        }

        public static ReportGoalSnapshot absent() {
            return new ReportGoalSnapshot(null, false, false, null);
        }
    }

    public record PersonalGoalSnapshot(Long goalId, String goalName, Boolean done) {
        public PersonalGoalSnapshot {
            if (goalId == null || goalId <= 0) {
                throw new IllegalArgumentException("goalId must be positive");
            }
            if (goalName == null || goalName.isBlank()) {
                throw new IllegalArgumentException("goalName must be visible");
            }
        }
    }
}
