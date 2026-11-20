package com.nekitvp.marathonbot.gamification.rule;

import java.time.LocalDate;

/** Inputs used by occurrence-key strategies; irrelevant fields remain null. */
public record OccurrenceKeyContext(LocalDate activityDate, Long goalId) {

    public static OccurrenceKeyContext empty() {
        return new OccurrenceKeyContext(null, null);
    }

    public static OccurrenceKeyContext onDate(LocalDate activityDate) {
        return new OccurrenceKeyContext(activityDate, null);
    }

    public static OccurrenceKeyContext forGoal(Long goalId) {
        return new OccurrenceKeyContext(null, goalId);
    }
}
