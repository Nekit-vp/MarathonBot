package com.nekitvp.marathonbot.sheduller;

import com.nekitvp.marathonbot.enumBot.MarathonStatus;
import com.nekitvp.marathonbot.gamification.model.ExpeditionStateEntity;
import com.nekitvp.marathonbot.gamification.model.RewardKind;
import com.nekitvp.marathonbot.gamification.model.RewardNotificationMode;
import com.nekitvp.marathonbot.gamification.repository.ExpeditionStateRepository;
import com.nekitvp.marathonbot.gamification.repository.RewardAwardRepository;
import com.nekitvp.marathonbot.gamification.rule.RewardCatalog;
import com.nekitvp.marathonbot.gamification.rule.RewardCode;
import com.nekitvp.marathonbot.gamification.rule.RewardEvaluationContext;
import com.nekitvp.marathonbot.gamification.rule.RewardDefinition;
import com.nekitvp.marathonbot.gamification.rule.OccurrenceKeyStrategy;
import com.nekitvp.marathonbot.gamification.rule.UserDaySnapshot;
import com.nekitvp.marathonbot.gamification.service.AchievementService;
import com.nekitvp.marathonbot.gamification.service.GamificationMessageComposer;
import com.nekitvp.marathonbot.gamification.service.GamificationNotificationService;
import com.nekitvp.marathonbot.gamification.service.GamificationService;
import com.nekitvp.marathonbot.gamification.service.GamificationSchedulingService;
import com.nekitvp.marathonbot.gamification.service.MarathonParticipationService;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.repository.UserRepository;
import com.nekitvp.marathonbot.service.GoalService;
import com.nekitvp.marathonbot.service.HistoryService;
import com.nekitvp.marathonbot.service.MarathonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class GamificationSchedulerTest {

    @Mock private HistoryService historyService;
    @Mock private MarathonService marathonService;
    @Mock private GoalService goalService;
    @Mock private GamificationSchedulingService gamificationSchedulingService;
    @Mock private GamificationNotificationService notificationService;

    private ReportScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ReportScheduler(historyService, marathonService, goalService,
                gamificationSchedulingService, notificationService);
    }

    @Test
    void midnightLifecycleFinishesBeforeIsolatedReconciliationRunsAt0005() {
        scheduler.handleLifecycle();
        scheduler.reconcileGamification();

        InOrder order = inOrder(historyService, marathonService, gamificationSchedulingService);
        order.verify(historyService).updateHistoryWhoForgotTwoDays();
        order.verify(historyService).createHistoryWhoForgotOneday();
        order.verify(marathonService).startMarathonsIfNeeded();
        order.verify(marathonService).finishMarathonsIfNeeded();
        order.verify(gamificationSchedulingService).reconcileAfterMidnight(any(LocalDateTime.class));
    }

    @Test
    void outboxDispatcherUsesBoundedBatches() {
        scheduler.dispatchGamificationNotifications();

        verify(notificationService).dispatchReady(any(LocalDateTime.class), eq(50));
    }

    @Test
    void gamificationJobsKeepMoscowCronMetadata() throws Exception {
        Scheduled reconciliation = ReportScheduler.class.getMethod("reconcileGamification")
                .getAnnotation(Scheduled.class);
        Scheduled dispatch = ReportScheduler.class.getMethod("dispatchGamificationNotifications")
                .getAnnotation(Scheduled.class);

        Scheduled digest = ReportScheduler.class.getMethod("publishGamificationDigest")
                .getAnnotation(Scheduled.class);

        assertThat(reconciliation.cron()).isEqualTo("0 5 0 * * ?");
        assertThat(reconciliation.zone()).isEqualTo("Europe/Moscow");
        assertThat(dispatch.cron()).isEqualTo("0 * * * * ?");
        assertThat(dispatch.zone()).isEqualTo("Europe/Moscow");
        assertThat(digest.cron()).isEqualTo("0 5 6 * * ?");
        assertThat(digest.zone()).isEqualTo("Europe/Moscow");
    }

    @Test
    void dailyDigestRunsAfterTheMorningStatistics() {
        scheduler.sendDailyStatistics();
        scheduler.publishGamificationDigest();

        InOrder order = inOrder(marathonService, gamificationSchedulingService);
        order.verify(marathonService).sendStatistics();
        order.verify(gamificationSchedulingService).publishDailyDigest(any(LocalDateTime.class));
    }
}

@ExtendWith(MockitoExtension.class)
class GamificationSchedulingServiceTest {

    private static final LocalDateTime MONDAY = LocalDateTime.of(2026, 8, 10, 0, 5);

    @Mock private MarathonRepository marathonRepository;
    @Mock private ExpeditionStateRepository stateRepository;
    @Mock private MarathonParticipationService participationService;
    @Mock private GamificationService gamificationService;
    @Mock private AchievementService achievementService;
    @Mock private RewardAwardRepository awardRepository;
    @Mock private UserRepository userRepository;
    @Mock private RewardCatalog rewardCatalog;
    @Mock private GamificationMessageComposer composer;
    @Mock private GamificationNotificationService notificationService;

    private GamificationSchedulingService service;

    @BeforeEach
    void setUp() {
        service = new GamificationSchedulingService(
                marathonRepository, stateRepository, participationService, gamificationService,
                achievementService, awardRepository, userRepository, rewardCatalog, composer,
                notificationService);
    }


    @Test
    void midnightReconciliationNoLongerPublishesAnything() {
        MarathonEntity marathon = marathon(1L, -100L);
        when(marathonRepository.findAll()).thenReturn(List.of(marathon));
        when(stateRepository.findFirstByMarathonIdOrderByRuleVersionDesc(1L))
                .thenReturn(Optional.of(state(1L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))));
        when(participationService.rosterAt(eq(1L), any())).thenReturn(List.of(10L));
        when(gamificationService.recalculateSafely(any())).thenReturn(AchievementService.NewRewards.empty());

        service.reconcileAfterMidnight(MONDAY);

        verify(gamificationService).recalculateSafely(any(GamificationService.RecalculationRequest.class));
        verifyNoInteractions(notificationService);
    }

    @Test
    void mondayMergesWeeklySummaryAndCheckpointIntoTheSingleDailyDigest() {
        MarathonEntity marathon = marathon(1L, -100L);
        ExpeditionStateEntity state = state(1L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        state.setLastAnnouncedCheckpoint(0);
        when(marathonRepository.findAll()).thenReturn(List.of(marathon));
        when(stateRepository.findFirstByMarathonIdOrderByRuleVersionDesc(1L)).thenReturn(Optional.of(state));
        when(participationService.rosterAt(eq(1L), any())).thenReturn(List.of(10L, 20L));
        when(achievementService.buildContext(eq(marathon), eq(10L), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(context(10L, LocalTime.of(10, 0)));
        when(achievementService.buildContext(eq(marathon), eq(20L), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(context(20L, LocalTime.of(8, 30)));
        when(awardRepository.findAllByMarathonIdAndRuleVersionAndRewardCodeIn(eq(1L), eq(1), anyList()))
                .thenReturn(List.of(com.nekitvp.marathonbot.gamification.model.RewardAwardEntity.builder()
                        .rewardCode(RewardCode.EXPEDITION_25.name()).build()));
        when(awardRepository
                .findAllByMarathonIdAndRuleVersionAndEarnedOnBetweenAndNotificationModeNotOrderByEarnedOnAscIdAsc(
                        eq(1L), eq(1), any(), any(), eq(RewardNotificationMode.BACKFILL)))
                .thenReturn(List.of());
        when(rewardCatalog.definition(1, RewardCode.EXPEDITION_25)).thenReturn(new RewardDefinition(
                1, RewardCode.EXPEDITION_25, RewardKind.TEAM, "🏕️", "Лагерь", "Описание", "25% маршрута", false,
                "Условие", OccurrenceKeyStrategy.TEAM_CHECKPOINT,
                new RewardDefinition.Tier("EXPEDITION", 1, 25)));
        when(composer.composeCheckpointCelebration(any(), eq(25), any(), any())).thenReturn("checkpoint");
        when(composer.composeWeeklySummary(any(), any(), anyList())).thenReturn("weekly");
        when(composer.composeDailyDigest(eq(LocalDate.of(2026, 8, 9)), anyList(), any(),
                eq("weekly\n\ncheckpoint"))).thenReturn(List.of("digest"));

        service.publishDailyDigest(MONDAY);

        verify(achievementService).persistWeeklyEarlyBird(eq(1L), eq(20L), eq(1),
                eq(LocalDate.of(2026, 8, 9)), eq(RewardNotificationMode.PENDING), eq(MONDAY));
        verify(notificationService).enqueueDailyHighlight(
                1L, MONDAY.toLocalDate(), -100L, "digest", MONDAY);
        verifyNoMoreInteractions(notificationService);
        assertThat(state.getLastDigestWeek()).isEqualTo("2026-W32");
        assertThat(state.getLastAnnouncedCheckpoint()).isEqualTo(25);
    }

    @Test
    void ordinaryDayStillPublishesRewardsAndProgressWithoutAWeeklyBlock() {
        MarathonEntity marathon = marathon(7L, -700L);
        ExpeditionStateEntity state = state(7L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        LocalDateTime tuesday = MONDAY.plusDays(1);
        when(marathonRepository.findAll()).thenReturn(List.of(marathon));
        when(stateRepository.findFirstByMarathonIdOrderByRuleVersionDesc(7L)).thenReturn(Optional.of(state));
        when(participationService.rosterAt(eq(7L), any())).thenReturn(List.of());
        when(awardRepository.findAllByMarathonIdAndRuleVersionAndRewardCodeIn(eq(7L), eq(1), anyList()))
                .thenReturn(List.of());
        when(awardRepository
                .findAllByMarathonIdAndRuleVersionAndEarnedOnBetweenAndNotificationModeNotOrderByEarnedOnAscIdAsc(
                        eq(7L), eq(1), any(), any(), eq(RewardNotificationMode.BACKFILL)))
                .thenReturn(List.of());
        when(composer.composeStatisticsProgress(25)).thenReturn("progress");
        when(composer.composeDailyDigest(eq(LocalDate.of(2026, 8, 10)), anyList(), eq("progress"), eq("")))
                .thenReturn(List.of("daily"));

        service.publishDailyDigest(tuesday);

        verify(composer, never()).composeWeeklySummary(any(), any(), anyList());
        verify(notificationService).enqueueDailyHighlight(
                7L, tuesday.toLocalDate(), -700L, "daily", tuesday);
    }

    @Test
    void repeatedMondayUsesPreviousIsoWeekKeyToDeduplicateTheWeeklyBlock() {
        MarathonEntity marathon = marathon(5L, -500L);
        ExpeditionStateEntity state = state(5L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        state.setLastDigestWeek("2026-W32");
        when(marathonRepository.findAll()).thenReturn(List.of(marathon));
        when(stateRepository.findFirstByMarathonIdOrderByRuleVersionDesc(5L)).thenReturn(Optional.of(state));
        when(participationService.rosterAt(eq(5L), any())).thenReturn(List.of());
        when(awardRepository.findAllByMarathonIdAndRuleVersionAndRewardCodeIn(eq(5L), eq(1), anyList()))
                .thenReturn(List.of());
        when(awardRepository
                .findAllByMarathonIdAndRuleVersionAndEarnedOnBetweenAndNotificationModeNotOrderByEarnedOnAscIdAsc(
                        eq(5L), eq(1), any(), any(), eq(RewardNotificationMode.BACKFILL)))
                .thenReturn(List.of());
        when(composer.composeDailyDigest(any(), anyList(), any(), eq(""))).thenReturn(List.of("daily"));

        service.publishDailyDigest(MONDAY);

        verify(achievementService, never()).persistWeeklyEarlyBird(any(), any(), anyInt(), any(), any(), any());
        verify(composer, never()).composeWeeklySummary(any(), any(), anyList());
        verify(notificationService).enqueueDailyHighlight(
                5L, MONDAY.toLocalDate(), -500L, "daily", MONDAY);
    }

    @Test
    void weekOverlappingTheRouteStartIsStillSummarised() {
        MarathonEntity marathon = marathon(2L, -200L);
        // Маршрут стартовал в среду 5 августа — неделя 3–9 августа пересекается, но не входит целиком.
        ExpeditionStateEntity state = state(2L, LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 31));
        when(marathonRepository.findAll()).thenReturn(List.of(marathon));
        when(stateRepository.findFirstByMarathonIdOrderByRuleVersionDesc(2L)).thenReturn(Optional.of(state));
        when(participationService.rosterAt(eq(2L), any())).thenReturn(List.of());
        when(awardRepository.findAllByMarathonIdAndRuleVersionAndRewardCodeIn(eq(2L), eq(1), anyList()))
                .thenReturn(List.of());
        when(awardRepository
                .findAllByMarathonIdAndRuleVersionAndEarnedOnBetweenAndNotificationModeNotOrderByEarnedOnAscIdAsc(
                        eq(2L), eq(1), any(), any(), eq(RewardNotificationMode.BACKFILL)))
                .thenReturn(List.of());
        when(composer.composeWeeklySummary(eq(LocalDate.of(2026, 8, 5)), eq(LocalDate.of(2026, 8, 9)), anyList()))
                .thenReturn("weekly");
        when(composer.composeDailyDigest(any(), anyList(), any(), eq("weekly"))).thenReturn(List.of("daily"));

        service.publishDailyDigest(MONDAY);

        verify(notificationService).enqueueDailyHighlight(
                2L, MONDAY.toLocalDate(), -200L, "daily", MONDAY);
        assertThat(state.getLastDigestWeek()).isEqualTo("2026-W32");
    }

    @Test
    void marathonWithoutAGroupIsSkippedWithoutTouchingTheOutbox() {
        MarathonEntity marathon = marathon(8L, null);
        when(marathonRepository.findAll()).thenReturn(List.of(marathon));

        service.publishDailyDigest(MONDAY);

        verifyNoInteractions(notificationService);
    }

    @Test
    void damagedMarathonDoesNotPreventTheNextDigest() {
        MarathonEntity damaged = marathon(3L, -300L);
        MarathonEntity healthy = marathon(4L, -400L);
        ExpeditionStateEntity healthyState = state(4L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        LocalDateTime tuesday = MONDAY.plusDays(1);
        when(marathonRepository.findAll()).thenReturn(List.of(damaged, healthy));
        when(stateRepository.findFirstByMarathonIdOrderByRuleVersionDesc(3L))
                .thenThrow(new IllegalStateException("broken"));
        when(stateRepository.findFirstByMarathonIdOrderByRuleVersionDesc(4L)).thenReturn(Optional.of(healthyState));
        when(participationService.rosterAt(eq(4L), any())).thenReturn(List.of());
        when(awardRepository.findAllByMarathonIdAndRuleVersionAndRewardCodeIn(eq(4L), eq(1), anyList()))
                .thenReturn(List.of());
        when(awardRepository
                .findAllByMarathonIdAndRuleVersionAndEarnedOnBetweenAndNotificationModeNotOrderByEarnedOnAscIdAsc(
                        eq(4L), eq(1), any(), any(), eq(RewardNotificationMode.BACKFILL)))
                .thenReturn(List.of());
        when(composer.composeDailyDigest(any(), anyList(), any(), eq(""))).thenReturn(List.of("daily"));

        service.publishDailyDigest(tuesday);

        verify(notificationService).enqueueDailyHighlight(
                4L, tuesday.toLocalDate(), -400L, "daily", tuesday);
    }
    private static MarathonEntity marathon(Long id, Long groupId) {
        return MarathonEntity.builder()
                .id(id).name("M" + id).groupId(groupId)
                .dateStart(LocalDate.of(2026, 8, 1).atStartOfDay())
                .dateEnd(LocalDate.of(2026, 8, 31).atTime(23, 59, 59))
                .status(MarathonStatus.ACTIVE).gamificationEnabled(true)
                .build();
    }

    private static ExpeditionStateEntity state(Long marathonId, LocalDate start, LocalDate end) {
        return ExpeditionStateEntity.builder()
                .marathonId(marathonId).ruleVersion(1).activatedAt(start.atStartOfDay())
                .routeStartDate(start).routeEndDate(end).plannedActiveDays(20)
                .distance(new BigDecimal("25.0000")).lastAnnouncedCheckpoint(25).build();
    }

    private static RewardEvaluationContext context(Long userId, LocalTime submittedAt) {
        LocalDate reportDate = LocalDate.of(2026, 8, 4);
        UserDaySnapshot day = new UserDaySnapshot(reportDate, false, false,
                new UserDaySnapshot.ReportGoalSnapshot(true, true, submittedAt), List.of());
        return new RewardEvaluationContext(1L, userId, 1, LocalDate.of(2026, 8, 9),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                ZoneId.of("Europe/Moscow"), false, List.of(day));
    }
}
