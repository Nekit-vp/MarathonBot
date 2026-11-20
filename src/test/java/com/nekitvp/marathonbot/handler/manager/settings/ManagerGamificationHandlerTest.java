package com.nekitvp.marathonbot.handler.manager.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.MarathonStatus;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.gamification.service.GamificationActivationService;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerGamificationHandlerTest {

    private static final long MANAGER_ID = 101L;
    private static final long SUPERADMIN_ID = 999L;
    private static final long MARATHON_ID = 35L;

    @Mock private UserService userService;
    @Mock private MarathonRepository marathonRepository;
    @Mock private GamificationActivationService activationService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MarathonEntity marathon;

    @BeforeEach
    void setUp() throws Exception {
        marathon = MarathonEntity.builder()
                .id(MARATHON_ID)
                .name("Марафон v8")
                .status(MarathonStatus.ACTIVE)
                .groupId(-100L)
                .gamificationEnabled(false)
                .build();
        WizardContext context = new WizardContext();
        context.setSelectedMarathonId(MARATHON_ID);
        context.setPendingGamificationEnabled(true);
        lenient().when(userService.findByTelegramId(MANAGER_ID)).thenReturn(UserEntity.builder()
                .telegramId(MANAGER_ID)
                .inputContext(objectMapper.writeValueAsString(context))
                .managedMarathons(Set.of(marathon))
                .build());
        lenient().when(userService.getManagedMarathons(MANAGER_ID)).thenReturn(Set.of(marathon));
        lenient().when(marathonRepository.findById(MARATHON_ID)).thenReturn(Optional.of(marathon));
    }

    @Test
    void managerSeesStatusCataloguePreviewAndExplicitConfirmation() {
        Object response = previewHandler(MANAGER_ID).handle(callback(MANAGER_ID));

        SendMessage message = (SendMessage) response;
        assertThat(message.getText())
                .contains("выключены", "медал", "достижен", "экспедиц", "подтверд");
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) message.getReplyMarkup();
        assertThat(keyboard.getKeyboard().stream()
                .flatMap(java.util.Collection::stream)
                .map(button -> button.getCallbackData()))
                .contains(StateBot.MANAGER_GAMIFICATION.name())
                .doesNotContain(StateBot.MANAGER_TOGGLE_GAMIFICATION.name());
    }

    @Test
    void unrelatedManagerCannotPreviewOrToggleSelectedMarathon() {
        when(userService.getManagedMarathons(MANAGER_ID)).thenReturn(Set.of());

        Object preview = previewHandler(MANAGER_ID).handle(callback(MANAGER_ID));
        Object toggle = toggleHandler(MANAGER_ID, true).handle(callback(MANAGER_ID));

        assertThat(((SendMessage) preview).getText()).contains("Нет доступа");
        assertThat(((SendMessage) toggle).getText()).contains("Нет доступа");
        verify(activationService, never()).enable(MARATHON_ID, MANAGER_ID);
    }

    @Test
    void superadminCanPreviewAnySelectedMarathon() throws Exception {
        WizardContext context = new WizardContext();
        context.setSelectedMarathonId(MARATHON_ID);
        when(userService.findByTelegramId(SUPERADMIN_ID)).thenReturn(UserEntity.builder()
                .telegramId(SUPERADMIN_ID)
                .inputContext(objectMapper.writeValueAsString(context))
                .build());

        Object response = previewHandler(SUPERADMIN_ID).handle(callback(SUPERADMIN_ID));

        assertThat(((SendMessage) response).getText()).contains("Марафон v8", "выключены");
    }

    @Test
    void productionEnableIsHardDisabledButExistingGameCanBeDisabled() {
        Object disabled = toggleHandler(MANAGER_ID, false).handle(callback(MANAGER_ID));
        assertThat(((SendMessage) disabled).getText()).contains("ещё недоступно");
        verify(activationService, never()).enable(MARATHON_ID, MANAGER_ID);

        marathon.setGamificationEnabled(true);
        setIntent(MANAGER_ID, false);
        when(activationService.disable(MARATHON_ID))
                .thenReturn(GamificationActivationService.ActivationResult.disabled(MARATHON_ID));
        Object response = toggleHandler(MANAGER_ID, false).handle(callback(MANAGER_ID));
        assertThat(((SendMessage) response).getText()).contains("выключены", "сохранены");
        verify(activationService).disable(MARATHON_ID);
    }

    @Test
    void replayedEnableConfirmationCannotAccidentallyDisableGame() {
        marathon.setGamificationEnabled(true);
        setIntent(MANAGER_ID, true);
        when(activationService.enable(MARATHON_ID, MANAGER_ID))
                .thenReturn(GamificationActivationService.ActivationResult.alreadyEnabled(MARATHON_ID));

        Object response = toggleHandler(MANAGER_ID, true).handle(callback(MANAGER_ID));

        verify(activationService).enable(MARATHON_ID, MANAGER_ID);
        verify(activationService, never()).disable(MARATHON_ID);
        assertThat(((SendMessage) response).getText()).contains("включены", "Повторная презентация не требуется");
    }

    @Test
    void missingGroupAndUnsupportedStatesHaveClearMessages() {
        marathon.setGroupId(null);
        assertThat(((SendMessage) previewHandler(MANAGER_ID).handle(callback(MANAGER_ID))).getText())
                .contains("привяжи");

        marathon.setGroupId(-100L);
        marathon.setStatus(MarathonStatus.DRAFT);
        assertThat(((SendMessage) previewHandler(MANAGER_ID).handle(callback(MANAGER_ID))).getText())
                .contains("черновик");

        marathon.setStatus(MarathonStatus.FINISHED);
        assertThat(((SendMessage) previewHandler(MANAGER_ID).handle(callback(MANAGER_ID))).getText())
                .contains("завершён");
    }

    @Test
    void rolloutEnabledPreviewExposesConfirmationThenToggleUsesManagerCorrelation() {
        Object preview = new ManagerGamificationHandler(
                userService, marathonRepository, objectMapper, SUPERADMIN_ID, true).handle(callback(MANAGER_ID));
        assertThat(((InlineKeyboardMarkup) ((SendMessage) preview).getReplyMarkup()).getKeyboard().stream()
                .flatMap(java.util.Collection::stream)
                .map(button -> button.getCallbackData()))
                .contains(StateBot.MANAGER_TOGGLE_GAMIFICATION.name());

        setIntent(MANAGER_ID, true);
        when(activationService.enable(MARATHON_ID, MANAGER_ID))
                .thenReturn(GamificationActivationService.ActivationResult.enabled(MARATHON_ID, true, true));
        toggleHandler(MANAGER_ID, true).handle(callback(MANAGER_ID));

        verify(activationService).enable(MARATHON_ID, MANAGER_ID);
    }

    private ManagerGamificationHandler previewHandler(long userId) {
        return new ManagerGamificationHandler(
                userService, marathonRepository, objectMapper, SUPERADMIN_ID, false);
    }

    private ManagerToggleGamificationHandler toggleHandler(long userId, boolean enableAvailable) {
        return new ManagerToggleGamificationHandler(
                userService, marathonRepository, activationService, objectMapper, SUPERADMIN_ID, enableAvailable);
    }

    private void setIntent(long userId, boolean enabled) {
        WizardContext context = new WizardContext();
        context.setSelectedMarathonId(MARATHON_ID);
        context.setPendingGamificationEnabled(enabled);
        try {
            when(userService.findByTelegramId(userId)).thenReturn(UserEntity.builder()
                    .telegramId(userId)
                    .inputContext(objectMapper.writeValueAsString(context))
                    .build());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static Update callback(long userId) {
        Message message = new Message();
        message.setMessageId(10);
        Chat chat = new Chat();
        chat.setId(userId);
        message.setChat(chat);
        User from = new User(userId, "manager", false);
        CallbackQuery callback = new CallbackQuery();
        callback.setFrom(from);
        callback.setMessage(message);
        callback.setData(StateBot.MANAGER_GAMIFICATION.name());
        Update update = new Update();
        update.setCallbackQuery(callback);
        return update;
    }
}
