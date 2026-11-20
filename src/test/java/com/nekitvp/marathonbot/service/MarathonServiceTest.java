package com.nekitvp.marathonbot.service;

import com.nekitvp.marathonbot.enumBot.MarathonStatus;
import com.nekitvp.marathonbot.gamification.service.GamificationSchedulingService;
import com.nekitvp.marathonbot.integration.yandex.service.YandexGptService;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;

import static com.nekitvp.marathonbot.util.MessageTemplate.MARATHON_FINISH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarathonServiceTest {

    @Mock
    private MarathonRepository marathonRepository;

    @Mock
    private HistoryService historyService;

    @Mock
    private LetterSender letterSender;

    @Mock
    private UserService userService;

    @Mock
    private YandexGptService yandexGptService;

    @Mock
    private GamificationSchedulingService gamificationSchedulingService;

    @Mock
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private MarathonService marathonService;

    @BeforeEach
    void setUp() {
        marathonService = new MarathonService(
                marathonRepository, historyService, letterSender, userService, yandexGptService,
                gamificationSchedulingService, transactionTemplate);
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> callback =
                    invocation.getArgument(0);
            callback.accept(org.mockito.Mockito.mock(org.springframework.transaction.TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void finishesActiveMarathonEvenWhenSchedulerMissedItsFinalDate() {
        LocalDate finalDate = LocalDate.now().minusMonths(3);
        MarathonEntity marathon = MarathonEntity.builder()
                .id(34L)
                .name("Марафон Новая Жизнь 2.0")
                .groupId(-5079981285L)
                .dateEnd(finalDate.atTime(23, 59, 59))
                .status(MarathonStatus.ACTIVE)
                .restOnSaturday(false)
                .restOnSunday(false)
                .managers(new HashSet<>())
                .build();
        when(marathonRepository.findByStatusAndDateEndBefore(
                eq(MarathonStatus.ACTIVE), any(LocalDateTime.class)))
                .thenReturn(List.of(marathon));
        when(userService.getUsersByMarathonId(marathon.getId())).thenReturn(List.of());

        marathonService.finishMarathonsIfNeeded();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(marathonRepository).findByStatusAndDateEndBefore(
                eq(MarathonStatus.ACTIVE), cutoff.capture());
        assertEquals(LocalDate.now().atStartOfDay(), cutoff.getValue());
        verify(historyService).createFinalDayReports(marathon, finalDate);
        verify(gamificationSchedulingService).reconcileBeforeFinish(eq(marathon), eq(finalDate), any());
        verify(letterSender).publishEscape(marathon.getGroupId(), MARATHON_FINISH, marathon.getName());
        verify(letterSender).sendFinalStatistics(eq(marathon), any());
        assertEquals(MarathonStatus.FINISHED, marathon.getStatus());
        verify(marathonRepository).save(marathon);
    }

    @Test
    void doesNothingWhenNoActiveMarathonsAreOverdue() {
        when(marathonRepository.findByStatusAndDateEndBefore(
                eq(MarathonStatus.ACTIVE), any(LocalDateTime.class)))
                .thenReturn(List.of());

        marathonService.finishMarathonsIfNeeded();

        verify(marathonRepository, never()).save(any());
        verify(historyService, never()).createFinalDayReports(any(), any());
    }

    @Test
    void keepsFinishedStatusWhenFinalNotificationFails() {
        LocalDate finalDate = LocalDate.now().minusDays(1);
        MarathonEntity marathon = MarathonEntity.builder()
                .id(42L)
                .name("Завершённый марафон")
                .groupId(-100L)
                .dateEnd(finalDate.atTime(23, 59, 59))
                .status(MarathonStatus.ACTIVE)
                .managers(new HashSet<>())
                .build();
        when(marathonRepository.findByStatusAndDateEndBefore(
                eq(MarathonStatus.ACTIVE), any(LocalDateTime.class)))
                .thenReturn(List.of(marathon));
        doThrow(new RuntimeException("Telegram unavailable"))
                .when(letterSender).publishEscape(marathon.getGroupId(), MARATHON_FINISH, marathon.getName());

        marathonService.finishMarathonsIfNeeded();

        assertEquals(MarathonStatus.FINISHED, marathon.getStatus());
        verify(marathonRepository).save(marathon);
    }

    @Test
    void reconcilesBeforeChangingStatusOrPublishingFinishMessages() {
        LocalDate finalDate = LocalDate.now().minusDays(1);
        MarathonEntity marathon = marathon(51L, -151L, finalDate);
        when(marathonRepository.findByStatusAndDateEndBefore(eq(MarathonStatus.ACTIVE), any()))
                .thenReturn(List.of(marathon));
        when(userService.getUsersByMarathonId(marathon.getId())).thenReturn(List.of());

        marathonService.finishMarathonsIfNeeded();

        var order = inOrder(historyService, gamificationSchedulingService, marathonRepository, letterSender);
        order.verify(historyService).createFinalDayReports(marathon, finalDate);
        order.verify(gamificationSchedulingService).reconcileBeforeFinish(eq(marathon), eq(finalDate), any());
        order.verify(marathonRepository).save(marathon);
        order.verify(letterSender).publishEscape(marathon.getGroupId(), MARATHON_FINISH, marathon.getName());
        order.verify(letterSender).sendFinalStatistics(eq(marathon), any());
    }

    @Test
    void isolatesFailureOfOneFinishingMarathon() {
        LocalDate finalDate = LocalDate.now().minusDays(1);
        MarathonEntity damaged = marathon(61L, -161L, finalDate);
        MarathonEntity healthy = marathon(62L, -162L, finalDate);
        when(marathonRepository.findByStatusAndDateEndBefore(eq(MarathonStatus.ACTIVE), any()))
                .thenReturn(List.of(damaged, healthy));
        doThrow(new IllegalStateException("damaged gamification state"))
                .when(gamificationSchedulingService).reconcileBeforeFinish(eq(damaged), eq(finalDate), any());
        when(userService.getUsersByMarathonId(healthy.getId())).thenReturn(List.of());

        marathonService.finishMarathonsIfNeeded();

        assertEquals(MarathonStatus.ACTIVE, damaged.getStatus());
        assertEquals(MarathonStatus.FINISHED, healthy.getStatus());
        verify(letterSender).publishEscape(healthy.getGroupId(), MARATHON_FINISH, healthy.getName());
    }

    @Test
    void embedsExpeditionProgressInExistingStatisticMessage() {
        MarathonEntity marathon = MarathonEntity.builder()
                .id(71L)
                .name("Экспедиция")
                .groupId(-171L)
                .dateStart(LocalDate.now().minusDays(2).atStartOfDay())
                .dateEnd(LocalDate.now().plusDays(20).atTime(23, 59, 59))
                .gamificationEnabled(true)
                .build();
        when(marathonRepository.findAll()).thenReturn(List.of(marathon));
        when(userService.getUsersByMarathonId(marathon.getId())).thenReturn(List.of());
        when(gamificationSchedulingService.statisticsProgress(marathon))
                .thenReturn("🥾 *Экспедиция*: ▰▰▱▱ 42%");

        marathonService.sendStatistics();

        verify(letterSender).sendStatistics(eq(marathon), any(), eq("🥾 *Экспедиция*: ▰▰▱▱ 42%"));
        verify(letterSender, never()).publish(eq(marathon.getGroupId()), eq("🥾 *Экспедиция*: ▰▰▱▱ 42%"));
    }

    private static MarathonEntity marathon(Long id, Long groupId, LocalDate finalDate) {
        return MarathonEntity.builder()
                .id(id)
                .name("Марафон " + id)
                .groupId(groupId)
                .dateEnd(finalDate.atTime(23, 59, 59))
                .status(MarathonStatus.ACTIVE)
                .restOnSaturday(false)
                .restOnSunday(false)
                .managers(new HashSet<>())
                .build();
    }
}
