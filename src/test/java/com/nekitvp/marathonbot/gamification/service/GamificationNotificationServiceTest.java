package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.bot.TelegramBot;
import com.nekitvp.marathonbot.gamification.event.GamificationDeliveryResultEvent;
import com.nekitvp.marathonbot.gamification.model.GamificationNotificationEntity;
import com.nekitvp.marathonbot.gamification.model.GamificationNotificationStatus;
import com.nekitvp.marathonbot.gamification.model.GamificationNotificationType;
import com.nekitvp.marathonbot.gamification.repository.GamificationNotificationRepository;
import com.nekitvp.marathonbot.gamification.repository.ExpeditionStateRepository;
import com.nekitvp.marathonbot.gamification.support.PostgresTestContainerConfig;
import com.nekitvp.marathonbot.service.LetterSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GamificationNotificationServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 0, 5);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private GamificationNotificationRepository repository;
    private GamificationNotificationService service;
    private LetterSender letterSender;
    private TelegramBot telegramBot;
    private ExpeditionStateRepository stateRepository;
    private Long marathonId;

    @BeforeEach
    void setUp() {
        for (String table : List.of("gamification_notification", "marathon_participation", "expedition_state",
                "expedition_contribution", "reward_award", "history", "goal", "marathon_manager", "users",
                "motivation", "marathon")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        marathonId = jdbcTemplate.queryForObject(
                "INSERT INTO marathon(name,date_start,date_end,status) VALUES ('M',?,?, 'ACTIVE') RETURNING id",
                Long.class, NOW.toLocalDate().atStartOfDay(), NOW.plusDays(20));
        letterSender = mock(LetterSender.class);
        telegramBot = mock(TelegramBot.class);
        stateRepository = mock(ExpeditionStateRepository.class);
        service = new GamificationNotificationService(repository, jdbcTemplate,
                letterSender, telegramBot, stateRepository);
    }

    @Test
    void deterministicLaunchAndPerDateKeysDeduplicateCreation() {
        GamificationNotificationEntity first = service.enqueueLaunch(marathonId, 1, -100L, "launch", NOW);
        GamificationNotificationEntity duplicate = service.enqueueLaunch(marathonId, 1, -100L, "changed", NOW.plusMinutes(1));
        GamificationNotificationEntity highlight = service.enqueueDailyHighlight(
                marathonId, LocalDate.of(2026, 8, 10), -100L, "digest + checkpoint", NOW);

        assertThat(first.getId()).isEqualTo(duplicate.getId());
        assertThat(duplicate.getPayload()).isEqualTo("launch");
        assertThat(highlight.getOccurrenceKey()).isEqualTo("2026-08-10");
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void concurrentDispatchersAtomicallyClaimEachRowOnce() throws Exception {
        service.enqueueDailyHighlight(marathonId, NOW.toLocalDate(), -100L, "one", NOW);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<GamificationNotificationEntity>> a = pool.submit(() -> claim(ready, start));
            Future<List<GamificationNotificationEntity>> b = pool.submit(() -> claim(ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS).size() + b.get(20, TimeUnit.SECONDS).size()).isOne();
        } finally {
            pool.shutdownNow();
        }
        GamificationNotificationEntity row = repository.findAll().getFirst();
        assertThat(row.getStatus()).isEqualTo(GamificationNotificationStatus.SENDING);
        assertThat(row.getAttemptCount()).isOne();
        assertThat(row.getLastAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void dispatchMapsClaimedPayloadChatAndCorrelationToTelegram() {
        Long id = service.enqueueLaunch(marathonId, 1, -100L, "launch", NOW).getId();
        org.mockito.ArgumentCaptor<com.nekitvp.marathonbot.bot.DeliveryWithFeedback> request =
                org.mockito.ArgumentCaptor.forClass(com.nekitvp.marathonbot.bot.DeliveryWithFeedback.class);
        org.mockito.Mockito.when(letterSender.gamificationDelivery(id, -100L, "launch"))
                .thenReturn(new com.nekitvp.marathonbot.bot.DeliveryWithFeedback(
                        org.telegram.telegrambots.meta.api.methods.send.SendMessage.builder()
                                .chatId(-100L).text("launch").build(), null, null, id));

        assertThat(service.dispatchReady(NOW, 10)).isOne();

        org.mockito.Mockito.verify(telegramBot).executeWithFeedback(request.capture());
        assertThat(request.getValue().deliveryCorrelationId()).isEqualTo(id);
        assertThat(request.getValue().delivery().getChatId()).isEqualTo("-100");
        assertThat(request.getValue().delivery().getText()).isEqualTo("launch");
    }

    @Test
    void successAndFailureEventsDriveBoundedRetriesAndTimestamps() {
        Long id = service.enqueueLaunch(marathonId, 1, -100L, "launch", NOW).getId();
        assertThat(service.claimReady(NOW, 1)).hasSize(1);
        service.handleDeliveryResult(new GamificationDeliveryResultEvent(id, false, "network", NOW.plusSeconds(2)));

        GamificationNotificationEntity retry = repository.findById(id).orElseThrow();
        assertThat(retry.getStatus()).isEqualTo(GamificationNotificationStatus.PENDING);
        assertThat(retry.getNextAttemptAt()).isAfter(NOW);

        for (int attempt = 2; attempt <= GamificationNotificationService.MAX_ATTEMPTS; attempt++) {
            LocalDateTime due = repository.findById(id).orElseThrow().getNextAttemptAt();
            assertThat(service.claimReady(due, 1)).hasSize(1);
            service.handleDeliveryResult(new GamificationDeliveryResultEvent(id, false, "network", due.plusSeconds(1)));
        }
        GamificationNotificationEntity failed = repository.findById(id).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(GamificationNotificationStatus.FAILED);
        assertThat(failed.getAttemptCount()).isEqualTo(GamificationNotificationService.MAX_ATTEMPTS);
        assertThat(failed.getNextAttemptAt()).isNull();

        Long successId = service.enqueueDailyHighlight(marathonId, NOW.toLocalDate(), -100L, "daily", NOW).getId();
        service.claimReady(NOW, 1);
        service.handleDeliveryResult(new GamificationDeliveryResultEvent(successId, true, null, NOW.plusMinutes(1)));
        assertThat(repository.findById(successId).orElseThrow().getSentAt()).isEqualTo(NOW.plusMinutes(1));
    }

    @Test
    void launchDeliveryUpdatesSnapshotAndReportsTerminalOutcomeToManager() {
        var state = com.nekitvp.marathonbot.gamification.model.ExpeditionStateEntity.builder()
                .marathonId(marathonId).ruleVersion(1).build();
        org.mockito.Mockito.when(stateRepository.findFirstByMarathonIdOrderByRuleVersionDesc(marathonId))
                .thenReturn(java.util.Optional.of(state));
        Long successId = service.enqueueLaunch(marathonId, 1, -100L, 501L, "launch", NOW).getId();
        service.claimReady(NOW, 1);

        service.handleDeliveryResult(new GamificationDeliveryResultEvent(
                successId, true, null, NOW.plusSeconds(1)));

        assertThat(state.getLaunchAnnouncedAt()).isEqualTo(NOW.plusSeconds(1));
        org.mockito.Mockito.verify(letterSender).publishTextEscape(
                501L, "✅ Презентация игровой системы доставлена в чат марафона.");

        Long failedId = service.enqueue(marathonId, GamificationNotificationType.LAUNCH,
                "v2", -100L, 502L, "launch2", NOW).getId();
        for (int attempt = 1; attempt <= GamificationNotificationService.MAX_ATTEMPTS; attempt++) {
            LocalDateTime due = NOW.plusMinutes(attempt * 10L);
            service.claimReady(due, 1);
            service.handleDeliveryResult(new GamificationDeliveryResultEvent(
                    failedId, false, "network", due.plusSeconds(1)));
        }
        org.mockito.Mockito.verify(letterSender).publishTextEscape(
                org.mockito.ArgumentMatchers.eq(502L), org.mockito.ArgumentMatchers.contains("Не удалось доставить"));
    }

    @Test
    void restartFailsAmbiguousSendingRowsWithoutRiskingDuplicateDelivery() {
        Long id = service.enqueueLaunch(marathonId, 1, -100L, "launch", NOW).getId();
        service.claimReady(NOW, 1);

        assertThat(service.claimReady(NOW.plusMinutes(4), 1)).isEmpty();
        assertThat(service.claimReady(NOW.plusMinutes(6), 1)).isEmpty();
        assertThat(repository.findById(id).orElseThrow()).satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(GamificationNotificationStatus.FAILED);
            assertThat(row.getAttemptCount()).isOne();
            assertThat(row.getLastError()).contains("not retried");
        });
    }

    @Test
    void dailyUniqueKeyAllowsOnlyOneCheckpointAnnouncementPerCalendarDay() {
        LocalDate date = NOW.toLocalDate();
        service.enqueueDailyHighlight(marathonId, date, -100L, "checkpoint 25", NOW);
        service.enqueueDailyHighlight(marathonId, date, -100L, "checkpoint 50", NOW.plusSeconds(1));

        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getNotificationType()).isEqualTo(GamificationNotificationType.DAILY_HIGHLIGHT);
            assertThat(row.getPayload()).isEqualTo("checkpoint 25");
        });
    }

    private List<GamificationNotificationEntity> claim(CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("race did not start");
        return service.claimReady(NOW, 1);
    }
}
