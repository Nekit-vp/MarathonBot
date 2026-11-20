package com.nekitvp.marathonbot.gamification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.MarathonStatus;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.gamification.model.ExpeditionStateEntity;
import com.nekitvp.marathonbot.gamification.model.MarathonParticipationEntity;
import com.nekitvp.marathonbot.gamification.model.RewardNotificationMode;
import com.nekitvp.marathonbot.gamification.repository.ExpeditionStateRepository;
import com.nekitvp.marathonbot.gamification.repository.MarathonParticipationRepository;
import com.nekitvp.marathonbot.gamification.rule.RewardCatalog;
import com.nekitvp.marathonbot.handler.JoinMarathonConfirmHandler;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.integration.yandex.service.YandexGptService;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.repository.UserRepository;
import com.nekitvp.marathonbot.service.GoalService;
import com.nekitvp.marathonbot.service.HistoryService;
import com.nekitvp.marathonbot.service.LetterSender;
import com.nekitvp.marathonbot.service.MarathonService;
import com.nekitvp.marathonbot.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GamificationActivationServiceTest {

    private static final long MARATHON_ID = 35L;
    private static final LocalDateTime ACTIVATED_AT = LocalDateTime.of(2026, 8, 3, 9, 0);

    @Mock private MarathonRepository marathonRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExpeditionStateRepository stateRepository;
    @Mock private MarathonParticipationRepository participationRepository;
    @Mock private AchievementService achievementService;
    @Mock private GamificationService gamificationService;
    @Mock private GamificationMessageComposer messageComposer;
    @Mock private GamificationNotificationService notificationService;

    private MarathonParticipationService participationService;
    private GamificationActivationService service;
    private MarathonEntity marathon;

    @BeforeEach
    void setUp() {
        participationService = new MarathonParticipationService(participationRepository, stateRepository);
        service = new GamificationActivationService(
                marathonRepository, userRepository, stateRepository, participationService,
                achievementService, gamificationService, new RewardCatalog(), messageComposer,
                notificationService, () -> ACTIVATED_AT);
        lenient().when(messageComposer.composeLaunchPresentation(any(), any(Integer.class)))
                .thenReturn(List.of("launch"));
        marathon = MarathonEntity.builder()
                .id(MARATHON_ID)
                .name("Марафон v8")
                .dateStart(LocalDateTime.of(2026, 8, 1, 0, 0))
                .dateEnd(LocalDateTime.of(2026, 8, 12, 23, 59))
                .restOnSaturday(true)
                .restOnSunday(true)
                .status(MarathonStatus.ACTIVE)
                .groupId(-100L)
                .gamificationEnabled(false)
                .build();
        lenient().when(marathonRepository.findById(MARATHON_ID)).thenReturn(Optional.of(marathon));
    }

    @Test
    void firstEnableSnapshotsRouteSeedsRosterAndBackfillsPersonalRewardsOnly() {
        UserEntity first = participant(101L, marathon);
        UserEntity second = participant(102L, marathon);
        when(stateRepository.findFirstByMarathonIdOrderByRuleVersionDesc(MARATHON_ID)).thenReturn(Optional.empty());
        when(userRepository.findByMarathonId(MARATHON_ID)).thenReturn(List.of(first, second));
        when(participationRepository.findByMarathonIdAndUserIdAndLeftAtIsNull(any(), any()))
                .thenReturn(Optional.empty());

        GamificationActivationService.ActivationResult result = service.enable(MARATHON_ID);

        ArgumentCaptor<ExpeditionStateEntity> state = ArgumentCaptor.forClass(ExpeditionStateEntity.class);
        verify(stateRepository).save(state.capture());
        assertThat(state.getValue().getActivatedAt()).isEqualTo(ACTIVATED_AT);
        assertThat(state.getValue().getRouteStartDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(state.getValue().getRouteEndDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(state.getValue().getRestOnSaturday()).isTrue();
        assertThat(state.getValue().getRestOnSunday()).isTrue();
        assertThat(state.getValue().getPlannedActiveDays()).isEqualTo(8);
        verify(participationRepository, times(2)).save(any(MarathonParticipationEntity.class));
        verify(achievementService, times(2)).evaluateAndPersist(
                any(), eq(RewardNotificationMode.BACKFILL), eq(ACTIVATED_AT));
        verify(gamificationService, never()).recalculate(any());
        assertThat(marathon.getGamificationEnabled()).isTrue();
        assertThat(result.firstActivation()).isTrue();
        assertThat(result.launchRequired()).isTrue();
    }

    @Test
    void repeatedEnableIsIdempotentAndCannotRepeatBackfillOrLaunch() {
        marathon.setGamificationEnabled(true);
        ExpeditionStateEntity state = existingState();
        when(stateRepository.findFirstByMarathonIdOrderByRuleVersionDesc(MARATHON_ID))
                .thenReturn(Optional.of(state));

        GamificationActivationService.ActivationResult result = service.enable(MARATHON_ID);

        verify(stateRepository, never()).save(any());
        verify(achievementService, never()).evaluateAndPersist(any(), any(), any());
        verify(participationRepository, never()).save(any());
        assertThat(result.alreadyInRequestedState()).isTrue();
        assertThat(result.launchRequired()).isFalse();
    }

    @Test
    void disablePreservesStateAndReEnableSilentlyReconcilesDisabledInterval() {
        ExpeditionStateEntity state = existingState();
        state.setLastReconciledDate(LocalDate.of(2026, 8, 1));
        state.setRouteStartDate(LocalDate.of(2026, 8, 1));
        when(stateRepository.findFirstByMarathonIdOrderByRuleVersionDesc(MARATHON_ID))
                .thenReturn(Optional.of(state));
        UserEntity user = participant(101L, marathon);
        when(userRepository.findByMarathonId(MARATHON_ID)).thenReturn(List.of(user));
        when(gamificationService.recalculate(any())).thenReturn(AchievementService.NewRewards.empty());
        when(participationRepository.findRosterAt(eq(MARATHON_ID), any(LocalDateTime.class)))
                .thenReturn(List.of(MarathonParticipationEntity.builder()
                        .marathonId(MARATHON_ID).userId(101L).joinedAt(state.getActivatedAt()).build()));

        service.disable(MARATHON_ID);
        assertThat(marathon.getGamificationEnabled()).isFalse();
        verify(stateRepository, never()).delete(any());

        GamificationActivationService.ActivationResult result = service.enable(MARATHON_ID);

        ArgumentCaptor<GamificationService.RecalculationRequest> requests =
                ArgumentCaptor.forClass(GamificationService.RecalculationRequest.class);
        verify(gamificationService, times(3)).recalculate(requests.capture());
        assertThat(requests.getAllValues()).extracting(GamificationService.RecalculationRequest::activityDate)
                .containsExactly(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 3));
        assertThat(requests.getAllValues()).allMatch(request ->
                request.notificationMode() == RewardNotificationMode.BACKFILL);
        assertThat(result.firstActivation()).isFalse();
        assertThat(result.launchRequired()).isTrue();
        assertThat(state.getRouteStartDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(state.getRestOnSaturday()).isTrue();
    }

    @Test
    void reEnableReconcilesHistoricalRosterMemberWhoHasSinceLeftMarathon() {
        ExpeditionStateEntity state = existingState();
        state.setRouteStartDate(LocalDate.of(2026, 8, 3));
        state.setLastReconciledDate(LocalDate.of(2026, 8, 3));
        when(stateRepository.findFirstByMarathonIdOrderByRuleVersionDesc(MARATHON_ID))
                .thenReturn(Optional.of(state));
        when(userRepository.findByMarathonId(MARATHON_ID)).thenReturn(List.of());
        when(participationRepository.findRosterAt(MARATHON_ID, LocalDate.of(2026, 8, 3).atTime(23, 59, 59)))
                .thenReturn(List.of(MarathonParticipationEntity.builder()
                        .marathonId(MARATHON_ID).userId(404L).joinedAt(state.getActivatedAt()).build()));
        when(gamificationService.recalculate(any())).thenReturn(AchievementService.NewRewards.empty());

        service.enable(MARATHON_ID);

        ArgumentCaptor<GamificationService.RecalculationRequest> request =
                ArgumentCaptor.forClass(GamificationService.RecalculationRequest.class);
        verify(gamificationService).recalculate(request.capture());
        assertThat(request.getValue().userId()).isEqualTo(404L);
        assertThat(request.getValue().notificationMode()).isEqualTo(RewardNotificationMode.BACKFILL);
    }

    @Test
    void participationTracksActivationJoinSwitchAndRemovalWithoutDuplicates() {
        when(stateRepository.existsByMarathonId(MARATHON_ID)).thenReturn(true);
        when(stateRepository.existsByMarathonId(77L)).thenReturn(true);
        when(participationRepository.findByMarathonIdAndUserIdAndLeftAtIsNull(MARATHON_ID, 101L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(MarathonParticipationEntity.builder()
                        .marathonId(MARATHON_ID).userId(101L).joinedAt(ACTIVATED_AT).build()));
        when(participationRepository.findByMarathonIdAndUserIdAndLeftAtIsNull(77L, 101L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(MarathonParticipationEntity.builder()
                        .marathonId(77L).userId(101L).joinedAt(ACTIVATED_AT.plusHours(1)).build()));

        participationService.join(MARATHON_ID, 101L, ACTIVATED_AT);
        participationService.join(MARATHON_ID, 101L, ACTIVATED_AT.plusMinutes(1));
        participationService.switchMarathon(MARATHON_ID, 77L, 101L, ACTIVATED_AT.plusHours(1));
        participationService.leave(77L, 101L, ACTIVATED_AT.plusHours(2));

        verify(participationRepository, times(4)).save(any(MarathonParticipationEntity.class));
    }

    @Test
    void directManagerAddAndRemoveSwitchParticipationThroughUserService() {
        MarathonParticipationService membership = mock(MarathonParticipationService.class);
        GoalService goalService = mock(GoalService.class);
        UserService users = new UserService(userRepository, marathonRepository, membership);
        ReflectionTestUtils.setField(users, "goalService", goalService);
        MarathonEntity previous = MarathonEntity.builder().id(12L).name("Старый").build();
        MarathonEntity target = MarathonEntity.builder().id(77L).name("Новый").build();
        UserEntity user = participant(101L, previous);
        when(userRepository.findByTelegramId(101L)).thenReturn(Optional.of(user));
        when(marathonRepository.findById(77L)).thenReturn(Optional.of(target));

        users.addUserToMarathon(101L, 77L);
        verify(membership).switchMarathon(eq(12L), eq(77L), eq(101L), any(LocalDateTime.class));

        users.removeUserFromMarathon(101L);
        verify(membership).leave(eq(77L), eq(101L), any(LocalDateTime.class));
    }

    @Test
    void inviteHandleJoinUsesSameMembershipAwareUserServicePath() {
        UserService users = mock(UserService.class);
        LetterSender letterSender = mock(LetterSender.class);
        MarathonService marathons = new MarathonService(
                marathonRepository,
                mock(HistoryService.class),
                letterSender,
                users,
                mock(YandexGptService.class),
                mock(GamificationSchedulingService.class),
                mock(org.springframework.transaction.support.TransactionTemplate.class));
        marathon.setGroupId(null);
        UserEntity user = participant(101L, null);

        String result = marathons.handleJoin(user, MARATHON_ID);

        verify(users).addUserToMarathon(101L, MARATHON_ID);
        verify(letterSender, never()).sendJoinMessage(any(), any());
        assertThat(result).contains("Добро пожаловать");
    }

    @Test
    void confirmedMarathonSwitchDelegatesToInviteJoinExactlyOnce() throws Exception {
        UserService users = mock(UserService.class);
        MarathonService marathons = mock(MarathonService.class);
        ObjectMapper mapper = new ObjectMapper();
        WizardContext context = new WizardContext();
        context.setPendingJoinMarathonId(MARATHON_ID);
        UserEntity pending = UserEntity.builder()
                .telegramId(101L)
                .inputContext(mapper.writeValueAsString(context))
                .build();
        when(users.findByTelegramId(101L)).thenReturn(pending);
        when(users.getUser(101L)).thenReturn(pending);
        when(marathons.handleJoin(pending, MARATHON_ID)).thenReturn("Готово");

        Object response = new JoinMarathonConfirmHandler(users, marathons, mapper).handle(callback(101L));

        verify(users).updateInputContext(101L, null);
        verify(marathons, times(1)).handleJoin(pending, MARATHON_ID);
        verify(users).updateUserState(101L, StateBot.MARATHON);
        assertThat(response).isNotNull();
    }

    @Test
    void rejectsDraftFinishedAndRouteWithoutActiveDays() {
        marathon.setStatus(MarathonStatus.DRAFT);
        assertThatThrownBy(() -> service.enable(MARATHON_ID)).hasMessageContaining("черновик");

        marathon.setStatus(MarathonStatus.FINISHED);
        assertThatThrownBy(() -> service.enable(MARATHON_ID)).hasMessageContaining("Завершён");

        marathon.setStatus(MarathonStatus.ACTIVE);
        marathon.setDateEnd(ACTIVATED_AT.minusDays(1));
        marathon.setRestOnSunday(false);
        marathon.setRestOnSaturday(false);
        assertThatThrownBy(() -> service.enable(MARATHON_ID)).hasMessageContaining("активных дней");
    }

    private ExpeditionStateEntity existingState() {
        return ExpeditionStateEntity.builder()
                .id(1L)
                .marathonId(MARATHON_ID)
                .ruleVersion(1)
                .activatedAt(LocalDateTime.of(2026, 8, 1, 9, 0))
                .routeStartDate(LocalDate.of(2026, 8, 3))
                .routeEndDate(LocalDate.of(2026, 8, 12))
                .restOnSaturday(true)
                .restOnSunday(true)
                .plannedActiveDays(8)
                .build();
    }

    private static UserEntity participant(long id, MarathonEntity marathon) {
        return UserEntity.builder().telegramId(id).telegramFirstName("User " + id).marathon(marathon).build();
    }

    private static Update callback(long userId) {
        Message message = new Message();
        message.setMessageId(10);
        Chat chat = new Chat();
        chat.setId(userId);
        message.setChat(chat);
        User from = new User(userId, "participant", false);
        CallbackQuery callback = new CallbackQuery();
        callback.setFrom(from);
        callback.setMessage(message);
        callback.setData(StateBot.JOIN_MARATHON_CONFIRM.name());
        Update update = new Update();
        update.setCallbackQuery(callback);
        return update;
    }
}
