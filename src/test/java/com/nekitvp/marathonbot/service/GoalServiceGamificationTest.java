package com.nekitvp.marathonbot.service;

import com.nekitvp.marathonbot.gamification.service.HistoryMutationRecalculationEvent;
import com.nekitvp.marathonbot.model.GoalEntity;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.repository.GoalRepository;
import com.nekitvp.marathonbot.repository.HistoryRepository;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoalServiceGamificationTest {

    @Mock private GoalRepository goalRepository;
    @Mock private MarathonRepository marathonRepository;
    @Mock private HistoryRepository historyRepository;
    @Mock private UserService userService;
    @Mock private TemplatePhraseService templatePhraseService;
    @Mock private LetterSender letterSender;
    @Mock private ApplicationEventPublisher eventPublisher;

    private GoalService goalService;

    @BeforeEach
    void setUp() {
        goalService = new GoalService(goalRepository, marathonRepository, userService,
                templatePhraseService, letterSender, eventPublisher);
    }

    @Test
    void archivesGoalPreservesHistorySnapshotsAndRepositionsOnlyActiveGoals() {
        MarathonEntity marathon = MarathonEntity.builder().id(35L).build();
        GoalEntity archived = GoalEntity.builder()
                .id(10L).userId(123L).name("Читать 20 страниц").position(2).marathon(marathon).build();
        GoalEntity first = GoalEntity.builder().id(11L).userId(123L).position(1).marathon(marathon).build();
        GoalEntity third = GoalEntity.builder().id(12L).userId(123L).position(3).marathon(marathon).build();
        when(goalRepository.findByIdAndArchivedAtIsNull(10L)).thenReturn(Optional.of(archived));
        when(goalRepository.findAllByUserIdAndMarathonIdOrderByPosition(123L, 35L))
                .thenReturn(List.of(first, third));

        goalService.deleteGoal(10L);

        assertThat(archived.getArchivedAt()).isNotNull();
        assertThat(first.getPosition()).isEqualTo(1);
        assertThat(third.getPosition()).isEqualTo(2);
        verify(goalRepository).save(archived);
        verify(historyRepository, never()).deleteByGoalId(10L);
        verify(goalRepository, never()).deleteById(10L);

        ArgumentCaptor<HistoryMutationRecalculationEvent> event =
                ArgumentCaptor.forClass(HistoryMutationRecalculationEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().marathonId()).isEqualTo(35L);
        assertThat(event.getValue().userId()).isEqualTo(123L);
        assertThat(event.getValue().goalSnapshot()).isEqualTo(
                new HistoryMutationRecalculationEvent.GoalSnapshot(10L, "Читать 20 страниц"));
    }
}
