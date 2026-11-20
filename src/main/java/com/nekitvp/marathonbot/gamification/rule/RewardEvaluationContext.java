package com.nekitvp.marathonbot.gamification.rule;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Repository-free input window for deterministic personal reward evaluation. */
public record RewardEvaluationContext(
        Long marathonId,
        Long userId,
        int rulesVersion,
        LocalDate evaluationDate,
        LocalDate marathonStartDate,
        LocalDate marathonEndDate,
        ZoneId rulesZone,
        boolean marathonFinished,
        List<UserDaySnapshot> days
) {
    public RewardEvaluationContext {
        if (marathonId == null || marathonId <= 0 || userId == null || userId <= 0) {
            throw new IllegalArgumentException("Marathon and user IDs must be positive");
        }
        if (rulesVersion <= 0) {
            throw new IllegalArgumentException("Rules version must be positive");
        }
        Objects.requireNonNull(evaluationDate, "evaluationDate");
        Objects.requireNonNull(marathonStartDate, "marathonStartDate");
        Objects.requireNonNull(marathonEndDate, "marathonEndDate");
        Objects.requireNonNull(rulesZone, "rulesZone");
        if (marathonEndDate.isBefore(marathonStartDate)) {
            throw new IllegalArgumentException("Marathon end date cannot precede its start date");
        }

        List<UserDaySnapshot> orderedDays = Objects.requireNonNull(days, "days").stream()
                .sorted(Comparator.comparing(UserDaySnapshot::activityDate))
                .toList();
        Set<LocalDate> uniqueDates = new HashSet<>();
        for (UserDaySnapshot day : orderedDays) {
            if (!uniqueDates.add(day.activityDate())) {
                throw new IllegalArgumentException("Duplicate day snapshot: " + day.activityDate());
            }
        }
        days = List.copyOf(orderedDays);
    }
}
