package com.nekitvp.marathonbot.gamification.rule;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Stable persistence codes for rewards available under launch rules version 1. */
public enum RewardCode {
    STRONG_DAY,
    GREEN_DAY,
    EARLY_START,
    BEFORE_REMINDER,
    COMEBACK,
    EARLY_BIRD_WEEK,
    TEAM_DAY,
    FIRST_STEP,
    REPORT_STREAK_3,
    REPORT_STREAK_7,
    REPORT_STREAK_14,
    REPORT_STREAK_21,
    FULL_DISTANCE,
    GOAL_STREAK_7,
    GOAL_STREAK_14,
    GOAL_STREAK_21,
    GOALS_DONE_25,
    GOALS_DONE_50,
    GOALS_DONE_100,
    GOALS_DONE_200,
    GREEN_DAYS_3,
    GREEN_DAYS_7,
    GREEN_DAYS_14,
    GREEN_STREAK_3,
    GREEN_STREAK_5,
    GREEN_STREAK_10,
    STABILITY,
    PUSHED_TO_FINISH,
    EXPEDITION_25,
    EXPEDITION_50,
    EXPEDITION_75,
    EXPEDITION_100,
    ALL_ON_BOARD,
    TEAM_RHYTHM,
    PERFECT_TEAM_DAY,
    TEAM_WEEK;

    public static Set<String> names() {
        return Arrays.stream(values())
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
    }
}
