package com.nekitvp.marathonbot.handler.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

@Component
@RequiredArgsConstructor
public class ManagerAddManagerHandler extends AbstractManagerHandler {

    private final UserService userService;
    private final MarathonRepository marathonRepository;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_ADD_MANAGER;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long telegramUserId = getUserId(update);

        // On callback (button click) — show the prompt
        if (update.hasCallbackQuery()) {
            return getDefaultMessage(message, new InlineKeyboardBuilder().row(backButton()).build());
        }

        // On text input — find user and add as manager
        String input = message.getText().trim();

        WizardContext ctx = readCtx(telegramUserId, userService, objectMapper);
        Long marathonId = ctx.getSelectedMarathonId();
        if (marathonId == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Марафон не выбран.")
                    .replyMarkup(new InlineKeyboardBuilder().row(backButton()).build())
                    .build();
        }

        MarathonEntity marathon = marathonRepository.findById(marathonId).orElse(null);
        if (marathon == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Марафон не найден.")
                    .replyMarkup(new InlineKeyboardBuilder().row(backButton()).build())
                    .build();
        }

        UserEntity target = userService.findByInput(input);
        if (target == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("❌ Пользователь не найден. Убедись, что он зарегистрирован в боте.\n\nВведи @username или Telegram ID.")
                    .replyMarkup(new InlineKeyboardBuilder().row(backButton()).build())
                    .build();
        }

        boolean alreadyManager = target.getManagedMarathons().stream()
                .anyMatch(m -> m.getId().equals(marathonId));
        if (alreadyManager) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("⚠️ " + target.getEffectiveName() + " уже является менеджером марафона «" + marathon.getName() + "».")
                    .replyMarkup(new InlineKeyboardBuilder().row(backButton()).build())
                    .build();
        }

        userService.addMarathonManager(target.getTelegramId(), marathonId);

        return SendMessage.builder()
                .chatId(message.getChatId())
                .text("✅ " + target.getEffectiveName() + " добавлен(а) как менеджер марафона «" + marathon.getName() + "».")
                .replyMarkup(new InlineKeyboardBuilder().row(backButton()).build())
                .build();
    }

    private InlineKeyboardButton backButton() {
        return InlineKeyboardButton.builder()
                .text("Вернуться к настройкам")
                .callbackData(StateBot.MANAGER_SETTINGS.name())
                .build();
    }
}
