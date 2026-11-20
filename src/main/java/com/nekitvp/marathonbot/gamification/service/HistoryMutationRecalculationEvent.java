package com.nekitvp.marathonbot.gamification.service;

import java.time.LocalDate;
import java.util.Objects;

/** Scoped request emitted by a committed history/goal mutation. */
public record HistoryMutationRecalculationEvent(
        Long marathonId,
        Long userId,
        LocalDate activityDate,
        boolean marathonFinished,
        GoalSnapshot goalSnapshot
) {
    public HistoryMutationRecalculationEvent {
        if (marathonId == null || marathonId <= 0 || userId == null || userId <= 0) {
            throw new IllegalArgumentException("Marathon and user IDs must be positive");
        }
        Objects.requireNonNull(activityDate, "activityDate");
    }

    public static HistoryMutationRecalculationEvent history(
            Long marathonId,
            Long userId,
            LocalDate activityDate,
            boolean marathonFinished
    ) {
        return new HistoryMutationRecalculationEvent(
                marathonId, userId, activityDate, marathonFinished, null);
    }

    public record GoalSnapshot(Long id, String name) {
        public GoalSnapshot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }
    }
}
