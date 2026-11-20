package com.nekitvp.marathonbot.gamification.event;

import java.time.LocalDateTime;

/** Correlates Telegram execution with one claimed gamification outbox row. */
public record GamificationDeliveryResultEvent(
        Long notificationId,
        boolean successful,
        String error,
        LocalDateTime occurredAt
) {
}
