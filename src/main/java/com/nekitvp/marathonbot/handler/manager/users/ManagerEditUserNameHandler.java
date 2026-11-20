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

import static com.nekitvp.marathonbot.util.TelegramUtil.button;

@Component
@RequiredArgsConstructor
public class ManagerEditUserNameHandler extends AbstractManagerHandler {

    private final UserService userService;
    private final MarathonRepository marathonRepository;
    private final LetterSender letterSender;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_EDIT_USER_NAME;
    }

    @Override
    public StateBot getNextState() {
        return StateBot.MANAGER_USER_DETAIL;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long telegramUserId = getUserId(update);
        WizardContext ctx = readCtx(telegramUserId, userService, objectMapper);

        if (update.hasCallbackQuery()) {
            Long selectedUserId = ctx.getSelectedUserId();
            UserEntity current = selectedUserId != null ? userService.findByTelegramId(selectedUserId) : null;
            String currentName = current != null ? current.getEffectiveName() : null;
            String prompt = "✏️ Введи новое имя участника:"
                    + (currentName != null ? "\n\nТекущее имя: «" + currentName + "»" : "");
            return getDefaultMessage(message, prompt, new InlineKeyboardBuilder()
                    .row(backButton(selectedUserId)).build());
        }

        String newName = message.getText().trim();
        if (newName.isEmpty() || newName.length() > 100) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("❌ Имя должно быть от 1 до 100 символов.")
                    .replyMarkup(new InlineKeyboardBuilder().row(backButton(ctx.getSelectedUserId())).build())
                    .build();
        }

        Long selectedUserId = ctx.getSelectedUserId();
        if (selectedUserId == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Участник не выбран.")
                    .replyMarkup(new InlineKeyboardBuilder().row(usersListButton()).build())
                    .build();
        }

        UserEntity targetUser = userService.findByTelegramId(selectedUserId);
        if (targetUser == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Участник не найден.")
                    .replyMarkup(new InlineKeyboardBuilder().row(usersListButton()).build())
                    .build();
        }

        String oldName = targetUser.getEffectiveName();
        targetUser.setDisplayName(newName);
        userService.saveUser(targetUser);

        String managerName = userService.getUser(telegramUserId).getEffectiveName();
        Long marathonId = ctx.getSelectedMarathonId();
        if (marathonId != null) {
            marathonRepository.findById(marathonId).ifPresent(m -> {
                if (m.getGroupId() != null) {
                    letterSender.publishTextEscape(m.getGroupId(),
                            "✏️ Менеджер " + managerName + " изменил имя участника: " + oldName + " → " + newName);
                }
            });
        }

        return SendMessage.builder()
                .chatId(message.getChatId())
                .text("✅ Имя участника обновлено: «" + newName + "»")
                .replyMarkup(new InlineKeyboardBuilder().row(backButton(selectedUserId)).build())
                .build();
    }

    // Числовой callback → UpdateHandler вызовет getNextState() (MANAGER_USER_DETAIL) → showUserDetail()
    private InlineKeyboardButton backButton(Long selectedUserId) {
        if (selectedUserId != null) {
            return button("Вернуться к участнику", selectedUserId);
        }
        return usersListButton();
    }

    private InlineKeyboardButton usersListButton() {
        return InlineKeyboardButton.builder()
                .text("К списку участников")
                .callbackData(StateBot.MANAGER_USERS.name())
                .build();
    }
}
