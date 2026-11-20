package com.nekitvp.marathonbot.service;

import com.nekitvp.marathonbot.model.GoalEntity;
import com.nekitvp.marathonbot.model.HistoryEntity;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.repository.HistoryRepository;
import com.nekitvp.marathonbot.gamification.service.HistoryMutationRecalculationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoryServiceTest {

    @Mock
    private HistoryRepository historyRepository;

    @Mock
    private GoalService goalService;

    @Mock
    private UserService userService;

    @Mock
    private LetterSender letterSender;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private HistoryService historyService;

    @BeforeEach
    void setUp() {
        historyService = new HistoryService(historyRepository, goalService, userService, letterSender, eventPublisher);
    }

    @Test
    void createsMissingReportsOnActualFinalDate() {
        LocalDate finalDate = LocalDate.of(2026, 4, 10);
        MarathonEntity marathon = MarathonEntity.builder().id(34L).build();
        UserEntity user = UserEntity.builder().telegramId(123L).build();
        GoalEntity goal = GoalEntity.builder().id(456L).userId(user.getTelegramId()).build();
        when(userService.getUsersByMarathonId(marathon.getId())).thenReturn(List.of(user));
        when(historyRepository.existsByTelegramIdAndCreatedAtBetween(
                user.getTelegramId(), finalDate.atStartOfDay(), finalDate.atTime(LocalTime.MAX)))
                .thenReturn(false);
        when(goalService.getGoalByUser(user.getTelegramId())).thenReturn(List.of(goal));

        historyService.createFinalDayReports(marathon, finalDate);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<HistoryEntity>> histories = ArgumentCaptor.forClass(List.class);
        verify(historyRepository).saveAll(histories.capture());
        assertEquals(1, histories.getValue().size());
        assertEquals(finalDate.atTime(12, 0), histories.getValue().getFirst().getCreatedAt());
        assertFalse(histories.getValue().getFirst().getDone());
        verify(letterSender).sendBadReportFinal(user, List.of(goal));
        ArgumentCaptor<HistoryMutationRecalculationEvent> event =
                ArgumentCaptor.forClass(HistoryMutationRecalculationEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(marathon.getId(), event.getValue().marathonId());
        assertEquals(user.getTelegramId(), event.getValue().userId());
        assertEquals(finalDate, event.getValue().activityDate());
        assertEquals(true, event.getValue().marathonFinished());
    }

    @Test
    void publishesScopedRecalculationAfterManagerCorrection() {
        LocalDateTime createdAt = LocalDate.of(2026, 8, 1).atTime(9, 30);
        MarathonEntity marathon = MarathonEntity.builder().id(35L).build();
        GoalEntity goal = GoalEntity.builder().id(456L).userId(123L).marathon(marathon).build();
        HistoryEntity history = HistoryEntity.builder().id(789L).goal(goal).done(false).createdAt(createdAt).build();
        when(historyRepository.findById(789L)).thenReturn(java.util.Optional.of(history));

        historyService.updateHistoryStatus(789L, true);

        ArgumentCaptor<HistoryMutationRecalculationEvent> event =
                ArgumentCaptor.forClass(HistoryMutationRecalculationEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(35L, event.getValue().marathonId());
        assertEquals(123L, event.getValue().userId());
        assertEquals(createdAt.toLocalDate(), event.getValue().activityDate());
    }

    @Test
    void publishesScopedRecalculationAfterDayOff() {
        MarathonEntity marathon = MarathonEntity.builder().id(35L).build();
        GoalEntity reportGoal = GoalEntity.builder()
                .id(456L).userId(123L).position(0).marathon(marathon).build();
        when(goalService.getGoalByUser(123L)).thenReturn(List.of(reportGoal));

        historyService.createDayOff(123L);

        ArgumentCaptor<HistoryMutationRecalculationEvent> event =
                ArgumentCaptor.forClass(HistoryMutationRecalculationEvent.class);
        ArgumentCaptor<HistoryEntity> saved = ArgumentCaptor.forClass(HistoryEntity.class);
        verify(historyRepository).save(saved.capture());
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(35L, event.getValue().marathonId());
        assertEquals(123L, event.getValue().userId());
        assertEquals(saved.getValue().getCreatedAt().toLocalDate(), event.getValue().activityDate());
        assertEquals(true, saved.getValue().getDayOff());
    }

    @Test
    void publishesScopedRecalculationAfterReportDeletion() {
        LocalDateTime createdAt = LocalDate.now().atTime(8, 0);
        MarathonEntity marathon = MarathonEntity.builder().id(35L).build();
        GoalEntity goal = GoalEntity.builder().id(456L).userId(123L).marathon(marathon).build();
        HistoryEntity history = HistoryEntity.builder().id(789L).goal(goal).done(true).createdAt(createdAt).build();
        when(historyRepository.findByTelegramIdAndCreatedAtBetween(
                org.mockito.ArgumentMatchers.eq(123L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(history));

        historyService.deleteReport(123L);

        verify(historyRepository).deleteAll(List.of(history));
        ArgumentCaptor<HistoryMutationRecalculationEvent> event =
                ArgumentCaptor.forClass(HistoryMutationRecalculationEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(35L, event.getValue().marathonId());
        assertEquals(123L, event.getValue().userId());
        assertEquals(createdAt.toLocalDate(), event.getValue().activityDate());
    }

    @Test
    void emptyReportDeletionDoesNotPublishAnUnscopedRequest() {
        when(historyRepository.findByTelegramIdAndCreatedAtBetween(
                org.mockito.ArgumentMatchers.eq(123L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        historyService.deleteReport(123L);

        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publishesScopedRecalculationAfterAutomaticMissFinalization() {
        LocalDate missedDate = LocalDate.now().minusDays(2);
        MarathonEntity historicalMarathon = MarathonEntity.builder()
                .id(35L)
                .dateStart(missedDate.minusDays(3).atStartOfDay())
                .dateEnd(missedDate.plusDays(3).atTime(LocalTime.MAX))
                .build();
        GoalEntity goal = GoalEntity.builder()
                .id(456L).userId(123L).position(1).marathon(historicalMarathon).build();
        HistoryEntity history = HistoryEntity.builder()
                .id(789L).goal(goal).done(null).createdAt(missedDate.atTime(12, 0)).build();
        UserEntity movedUser = UserEntity.builder()
                .telegramId(123L)
                .marathon(MarathonEntity.builder().id(99L).build())
                .build();
        when(historyRepository.findAllByCreatedAtBetweenAndDoneIsNull(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(history));
        when(userService.getUser(123L)).thenReturn(movedUser);

        historyService.updateHistoryWhoForgotTwoDays();

        assertFalse(history.getDone());
        ArgumentCaptor<HistoryMutationRecalculationEvent> event =
                ArgumentCaptor.forClass(HistoryMutationRecalculationEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(35L, event.getValue().marathonId());
        assertEquals(123L, event.getValue().userId());
        assertEquals(missedDate, event.getValue().activityDate());
    }
}
