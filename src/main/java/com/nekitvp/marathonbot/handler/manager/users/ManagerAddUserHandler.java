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

import java.util.List;

import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Component
@RequiredArgsConstructor
public class ManagerAddUserHandler extends AbstractManagerHandler {

    private final UserService userService;
    private final MarathonRepository marathonRepository;
    private final LetterSender letterSender;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_ADD_USER;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long telegramUserId = getUserId(update);

        if (update.hasCallbackQuery()) {
            return getDefaultMessage(message, new InlineKeyboardBuilder()
                    .row(backToUsersButton()).build());
        }

        String input = message.getText().trim();
        WizardContext ctx = readCtx(telegramUserId, userService, objectMapper);
        Long marathonId = ctx.getSelectedMarathonId();

        if (marathonId == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Марафон не выбран.")
                    .replyMarkup(new InlineKeyboardBuilder()
                            .row(createButtonByState(StateBot.MANAGER_MAIN)).build())
                    .build();
        }

        UserEntity found = userService.findByInput(input);
        if (found == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("❌ Пользователь не найден.\n\nВведи @username (с собачкой) или Telegram ID. Пользователь должен был хотя бы раз написать боту.")
                    .replyMarkup(new InlineKeyboardBuilder().row(backToUsersButton()).build())
                    .build();
        }

        List<UserEntity> current = userService.getUsersByMarathonId(marathonId);
        boolean alreadyMember = current.stream()
                .anyMatch(u -> u.getTelegramId().equals(found.getTelegramId()));
        if (alreadyMember) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("ℹ️ " + found.getEffectiveName() + " уже является участником.")
                    .replyMarkup(new InlineKeyboardBuilder().row(backToUsersButton()).build())
                    .build();
        }

        userService.addUserToMarathon(found.getTelegramId(), marathonId);
        String addedName = found.getEffectiveName();
        marathonRepository.findById(marathonId).ifPresent(m -> {
            if (m.getGroupId() != null) {
                letterSender.publishTextEscape(m.getGroupId(),
                        "👤 Менеджер добавил участника " + addedName + " в марафон");
            }
        });
        return SendMessage.builder()
                .chatId(message.getChatId())
                .text("✅ " + addedName + " добавлен в марафон!")
                .replyMarkup(new InlineKeyboardBuilder().row(backToUsersButton()).build())
                .build();
    }

    private InlineKeyboardButton backToUsersButton() {
        return InlineKeyboardButton.builder()
                .text("Вернуться к списку участников")
                .callbackData(StateBot.MANAGER_USERS.name())
                .build();
    }
}
