package com.nekitvp.marathonbot.gamification.rule;

import com.nekitvp.marathonbot.gamification.model.RewardKind;

import java.util.Objects;

/** Visible, unescaped domain metadata for a reward under one rules version. */
public record RewardDefinition(
        int rulesVersion,
        RewardCode code,
        RewardKind kind,
        String emoji,
        String title,
        String description,
        String shortHint,
        boolean repeatable,
        String visibleCondition,
        OccurrenceKeyStrategy occurrenceKeyStrategy,
        Tier tier
) {
    /** Краткие подсказки печатаются рядом с названием в групповых сводках и должны оставаться короткими. */
    public static final int MAX_SHORT_HINT_LENGTH = 32;

    public RewardDefinition {
        if (rulesVersion <= 0) {
            throw new IllegalArgumentException("Rules version must be positive");
        }
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(kind, "kind");
        requireVisibleText(emoji, "emoji");
        requireVisibleText(title, "title");
        requireVisibleText(description, "description");
        requireVisibleText(shortHint, "shortHint");
        requireVisibleText(visibleCondition, "visibleCondition");
        if (shortHint.length() > MAX_SHORT_HINT_LENGTH) {
            throw new IllegalArgumentException(
                    "shortHint for " + code + " must not exceed " + MAX_SHORT_HINT_LENGTH + " characters");
        }
    }

    public String occurrenceKey(OccurrenceKeyContext context) {
        if (occurrenceKeyStrategy == null) {
            throw new IllegalStateException("No occurrence-key strategy configured for " + code);
        }
        return occurrenceKeyStrategy.create(this, context);
    }

    RewardDefinition withOccurrenceKeyStrategy(OccurrenceKeyStrategy strategy) {
        return new RewardDefinition(
                rulesVersion,
                code,
                kind,
                emoji,
                title,
                description,
                shortHint,
                repeatable,
                visibleCondition,
                strategy,
                tier
        );
    }

    private static void requireVisibleText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be visible");
        }
    }

    public record Tier(String family, int order, int threshold) {
        public Tier {
            requireVisibleText(family, "tier family");
            if (order <= 0 || threshold <= 0) {
                throw new IllegalArgumentException("Tier order and threshold must be positive");
            }
        }
    }
}
