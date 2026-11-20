package com.nekitvp.marathonbot.handler.manager.users;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.manager.AbstractManagerHandler;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.service.LetterSender;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Component
@RequiredArgsConstructor
public class ManagerUserDetailHandler extends AbstractManagerHandler {

    private final UserService userService;
    private final MarathonRepository marathonRepository;
    private final LetterSender letterSender;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_USER_DETAIL;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long telegramUserId = getUserId(update);

        WizardContext ctx = readCtx(telegramUserId, userService, objectMapper);

        if (isNumericCallback(message)) {
            Long selectedUserId = extractId(message);
            ctx.setSelectedUserId(selectedUserId);
            ctx.setPendingDeleteId(null);
            saveCtx(telegramUserId, ctx, userService, objectMapper);
            return showUserDetail(message, selectedUserId);
        }

        // State callback: check pending delete
        Long pendingDeleteId = ctx.getPendingDeleteId();
        if (pendingDeleteId != null) {
            UserEntity removedUser = userService.findByTelegramId(pendingDeleteId);
            String removedName = removedUser != null ? removedUser.getEffectiveName() : "участник";
            userService.removeUserFromMarathon(pendingDeleteId);
            ctx.setSelectedUserId(null);
            ctx.setPendingDeleteId(null);
            saveCtx(telegramUserId, ctx, userService, objectMapper);

            Long marathonId = ctx.getSelectedMarathonId();
            if (marathonId != null) {
                final String name = removedName;
                marathonRepository.findById(marathonId).ifPresent(m -> {
                    if (m.getGroupId() != null) {
                        letterSender.publishTextEscape(m.getGroupId(),
                                "👤 Менеджер удалил участника " + name + " из марафона");
                    }
                });
            }

            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("✅ Участник удалён из марафона.")
                    .replyMarkup(new InlineKeyboardBuilder()
                            .row(backToUsersButton())
                            .build())
                    .build();
        }

        Long selectedUserId = ctx.getSelectedUserId();
        if (selectedUserId == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Участник не выбран.")
                    .replyMarkup(new InlineKeyboardBuilder()
                            .row(backToUsersButton())
                            .build())
                    .build();
        }
        ctx.setPendingDeleteId(selectedUserId);
        saveCtx(telegramUserId, ctx, userService, objectMapper);
        return showDeleteConfirmation(message, selectedUserId);
    }

    private Object showUserDetail(Message message, Long selectedUserId) {
        UserEntity user = userService.findByTelegramId(selectedUserId);
        if (user == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Участник не найден.")
                    .replyMarkup(new InlineKeyboardBuilder().row(backToUsersButton()).build())
                    .build();
        }

        String info = "👤 Участник:\n" +
                "Имя: " + user.getEffectiveName() + "\n" +
                (user.getDisplayName() != null ? "Имя в Telegram: " + user.getTelegramFirstName() + "\n" : "") +
                (user.getTelegramUserName() != null ? "Username: @" + user.getTelegramUserName() + "\n" : "") +
                "Telegram ID: " + user.getTelegramId();

        return getDefaultMessage(message, info, new InlineKeyboardBuilder()
                .row(InlineKeyboardButton.builder()
                        .text("✏️ Изменить имя")
                        .callbackData(StateBot.MANAGER_EDIT_USER_NAME.name())
                        .build())
                .row(InlineKeyboardButton.builder()
                        .text("❌ Удалить из марафона")
                        .callbackData(StateBot.MANAGER_USER_DETAIL.name())
                        .build())
                .row(backToUsersButton())
                .build());
    }

    private Object showDeleteConfirmation(Message message, Long selectedUserId) {
        UserEntity user = userService.findByTelegramId(selectedUserId);
        String name = user != null ? user.getEffectiveName() : "участника";

        return getDefaultMessage(message,
                "⚠️ Подтверди удаление " + name + " из марафона.",
                new InlineKeyboardBuilder()
                        .row(InlineKeyboardButton.builder()
                                .text("✅ Да, удалить")
                                .callbackData(StateBot.MANAGER_USER_DETAIL.name())
                                .build())
                        .row(backToUsersButton())
                        .build());
    }

    private InlineKeyboardButton backToUsersButton() {
        return InlineKeyboardButton.builder()
                .text("Вернуться к списку участников")
                .callbackData(StateBot.MANAGER_USERS.name())
                .build();
    }
}
