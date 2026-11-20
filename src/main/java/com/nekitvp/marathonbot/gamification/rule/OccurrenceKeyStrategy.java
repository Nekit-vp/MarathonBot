package com.nekitvp.marathonbot.gamification.rule;

import java.time.LocalDate;
import java.time.temporal.WeekFields;

/** Defines the stable business occurrence represented by a persisted award row. */
public enum OccurrenceKeyStrategy {
    DATE {
        @Override
        String create(RewardDefinition definition, OccurrenceKeyContext context) {
            return "date:" + requiredDate(context);
        }
    },
    ISO_WEEK {
        @Override
        String create(RewardDefinition definition, OccurrenceKeyContext context) {
            LocalDate date = requiredDate(context);
            WeekFields iso = WeekFields.ISO;
            int weekYear = date.get(iso.weekBasedYear());
            int week = date.get(iso.weekOfWeekBasedYear());
            return "week:%d-W%02d".formatted(weekYear, week);
        }
    },
    GOAL {
        @Override
        String create(RewardDefinition definition, OccurrenceKeyContext context) {
            if (context == null || context.goalId() == null || context.goalId() <= 0) {
                throw new IllegalArgumentException("A positive goal ID is required for " + definition.code());
            }
            return "goal:" + context.goalId();
        }
    },
    TIER {
        @Override
        String create(RewardDefinition definition, OccurrenceKeyContext context) {
            return "tier:" + definition.code().name();
        }
    },
    FINISH {
        @Override
        String create(RewardDefinition definition, OccurrenceKeyContext context) {
            return "finish";
        }
    },
    TEAM_CHECKPOINT {
        @Override
        String create(RewardDefinition definition, OccurrenceKeyContext context) {
            RewardDefinition.Tier tier = definition.tier();
            if (tier == null || !"EXPEDITION".equals(tier.family())) {
                throw new IllegalStateException("Expedition tier is required for " + definition.code());
            }
            return "checkpoint:" + tier.threshold();
        }
    };

    abstract String create(RewardDefinition definition, OccurrenceKeyContext context);

    private static LocalDate requiredDate(OccurrenceKeyContext context) {
        if (context == null || context.activityDate() == null) {
            throw new IllegalArgumentException("An activity date is required for this occurrence key");
        }
        return context.activityDate();
    }
}
