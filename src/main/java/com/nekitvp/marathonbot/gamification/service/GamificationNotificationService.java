package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.bot.TelegramBot;
import com.nekitvp.marathonbot.gamification.event.GamificationDeliveryResultEvent;
import com.nekitvp.marathonbot.gamification.model.GamificationNotificationEntity;
import com.nekitvp.marathonbot.gamification.model.GamificationNotificationStatus;
import com.nekitvp.marathonbot.gamification.model.GamificationNotificationType;
import com.nekitvp.marathonbot.gamification.repository.GamificationNotificationRepository;
import com.nekitvp.marathonbot.gamification.repository.ExpeditionStateRepository;
import com.nekitvp.marathonbot.service.LetterSender;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Persistent outbox for the two allowed standalone gamification message types. */
@Service
public class GamificationNotificationService {

    public static final int MAX_ATTEMPTS = 3;
    public static final Duration SENDING_LEASE = Duration.ofMinutes(5);

    private static final String INSERT = """
            INSERT INTO gamification_notification(
                marathon_id, notification_type, occurrence_key, chat_id, feedback_chat_id, payload,
                status, attempt_count, next_attempt_at, last_attempt_at, sent_at, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, NULL, NULL, NULL, ?)
            ON CONFLICT (marathon_id, notification_type, occurrence_key) DO NOTHING
            RETURNING id
            """;

    private static final String CLAIM = """
            WITH candidates AS (
                SELECT id
                FROM gamification_notification
                WHERE attempt_count < ?
                  AND (
                    (status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= ?))
                  )
                ORDER BY id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE gamification_notification notification
            SET status = 'SENDING',
                attempt_count = notification.attempt_count + 1,
                last_attempt_at = ?,
                next_attempt_at = NULL
            FROM candidates
            WHERE notification.id = candidates.id
            RETURNING notification.id
            """;

    private static final String FAIL_AMBIGUOUS = """
            UPDATE gamification_notification
            SET status = 'FAILED', next_attempt_at = NULL,
                last_error = 'Delivery outcome unknown after dispatcher restart; not retried to prevent duplicate delivery'
            WHERE status = 'SENDING' AND last_attempt_at <= ?
            RETURNING id
            """;

    private final GamificationNotificationRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final LetterSender letterSender;
    private final TelegramBot telegramBot;
    private final ExpeditionStateRepository stateRepository;

    public GamificationNotificationService(
            GamificationNotificationRepository repository,
            JdbcTemplate jdbcTemplate,
            LetterSender letterSender,
            @Lazy TelegramBot telegramBot,
            ExpeditionStateRepository stateRepository
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.letterSender = Objects.requireNonNull(letterSender, "letterSender");
        this.telegramBot = Objects.requireNonNull(telegramBot, "telegramBot");
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
    }

    public GamificationNotificationEntity enqueueLaunch(
            Long marathonId,
            int rulesVersion,
            Long chatId,
            String payload,
            LocalDateTime createdAt
    ) {
        return enqueueLaunch(marathonId, rulesVersion, chatId, null, payload, createdAt);
    }

    public GamificationNotificationEntity enqueueLaunch(
            Long marathonId,
            int rulesVersion,
            Long chatId,
            Long feedbackChatId,
            String payload,
            LocalDateTime createdAt
    ) {
        if (rulesVersion <= 0) {
            throw new IllegalArgumentException("rulesVersion must be positive");
        }
        return enqueue(marathonId, GamificationNotificationType.LAUNCH,
                "v" + rulesVersion, chatId, feedbackChatId, payload, createdAt);
    }

    public GamificationNotificationEntity enqueueDailyHighlight(
            Long marathonId,
            LocalDate occurrenceDate,
            Long chatId,
            String payload,
            LocalDateTime createdAt
    ) {
        return enqueue(marathonId, GamificationNotificationType.DAILY_HIGHLIGHT,
                Objects.requireNonNull(occurrenceDate, "occurrenceDate").toString(), chatId, null, payload, createdAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GamificationNotificationEntity enqueue(
            Long marathonId,
            GamificationNotificationType type,
            String occurrenceKey,
            Long chatId,
            String payload,
            LocalDateTime createdAt
    ) {
        return enqueue(marathonId, type, occurrenceKey, chatId, null, payload, createdAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GamificationNotificationEntity enqueue(
            Long marathonId,
            GamificationNotificationType type,
            String occurrenceKey,
            Long chatId,
            Long feedbackChatId,
            String payload,
            LocalDateTime createdAt
    ) {
        requirePositive(marathonId, "marathonId");
        Objects.requireNonNull(type, "type");
        if (occurrenceKey == null || occurrenceKey.isBlank()) {
            throw new IllegalArgumentException("occurrenceKey must be visible");
        }
        requirePositiveOrNegative(chatId, "chatId");
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload must be visible");
        }
        Objects.requireNonNull(createdAt, "createdAt");

        jdbcTemplate.query(INSERT, (rs, row) -> rs.getLong(1),
                marathonId, type.name(), occurrenceKey, chatId, feedbackChatId, payload,
                Timestamp.valueOf(createdAt));
        return repository.findByMarathonIdAndNotificationTypeAndOccurrenceKey(marathonId, type, occurrenceKey)
                .orElseThrow();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<GamificationNotificationEntity> claimReady(LocalDateTime now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit <= 0) {
            return List.of();
        }
        List<Long> ambiguousIds = jdbcTemplate.query(
                FAIL_AMBIGUOUS, (rs, row) -> rs.getLong(1), Timestamp.valueOf(now.minus(SENDING_LEASE)));
        repository.findAllById(ambiguousIds).forEach(notification -> notifyManager(notification,
                "⚠️ Статус доставки презентации неизвестен после перезапуска. Повтор не отправлен, чтобы не дублировать сообщение."));
        List<Long> ids = jdbcTemplate.query(CLAIM, (rs, row) -> rs.getLong(1),
                MAX_ATTEMPTS,
                Timestamp.valueOf(now),
                limit,
                Timestamp.valueOf(now));
        return repository.findAllById(ids).stream()
                .sorted(java.util.Comparator.comparingLong(row -> ids.indexOf(row.getId())))
                .toList();
    }

    public int dispatchReady(LocalDateTime now, int limit) {
        List<GamificationNotificationEntity> claimed = claimReady(now, limit);
        claimed.forEach(notification -> telegramBot.executeWithFeedback(letterSender.gamificationDelivery(
                notification.getId(), notification.getChatId(), notification.getPayload())));
        return claimed.size();
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleDeliveryResult(GamificationDeliveryResultEvent event) {
        Objects.requireNonNull(event, "event");
        GamificationNotificationEntity notification = repository.findById(event.notificationId()).orElse(null);
        if (notification == null || notification.getStatus() != GamificationNotificationStatus.SENDING) {
            return;
        }
        if (event.successful()) {
            notification.setStatus(GamificationNotificationStatus.SENT);
            notification.setSentAt(event.occurredAt());
            notification.setNextAttemptAt(null);
            notification.setLastError(null);
            if (notification.getNotificationType() == GamificationNotificationType.LAUNCH) {
                markLaunchDelivered(notification, event.occurredAt());
                notifyManager(notification, "✅ Презентация игровой системы доставлена в чат марафона.");
            }
        } else if (notification.getAttemptCount() >= MAX_ATTEMPTS) {
            notification.setStatus(GamificationNotificationStatus.FAILED);
            notification.setNextAttemptAt(null);
            notification.setLastError(trimError(event.error()));
            notifyManager(notification,
                    "⚠️ Не удалось доставить презентацию игровой системы после нескольких попыток. "
                            + "Игра включена, награды сохранены; проверь привязку чата и права бота.");
        } else {
            notification.setStatus(GamificationNotificationStatus.PENDING);
            notification.setNextAttemptAt(event.occurredAt().plusMinutes(notification.getAttemptCount()));
            notification.setLastError(trimError(event.error()));
        }
        repository.save(notification);
    }

    private void markLaunchDelivered(GamificationNotificationEntity notification, LocalDateTime deliveredAt) {
        stateRepository.findFirstByMarathonIdOrderByRuleVersionDesc(notification.getMarathonId())
                .ifPresent(state -> {
                    if (state.getLaunchAnnouncedAt() == null) {
                        state.setLaunchAnnouncedAt(deliveredAt);
                        stateRepository.save(state);
                    }
                });
    }

    private void notifyManager(GamificationNotificationEntity notification, String text) {
        if (notification.getNotificationType() == GamificationNotificationType.LAUNCH
                && notification.getFeedbackChatId() != null) {
            letterSender.publishTextEscape(notification.getFeedbackChatId(), text);
        }
    }

    private static String trimError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requirePositiveOrNegative(Long value, String field) {
        if (value == null || value == 0) {
            throw new IllegalArgumentException(field + " must not be zero");
        }
    }
}
