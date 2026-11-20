package com.nekitvp.marathonbot.handler.manager.create;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.AbstractHandler;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.nekitvp.marathonbot.handler.manager.create.CreateMarathonInfoHandler.cancelButton;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreateMarathonDurationHandler extends AbstractHandler {

    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.CREATE_MARATHON_DURATION;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);

        // Initial display (edit existing message)
        if (update.hasCallbackQuery()) {
            return getDefaultMessage(message,
                    new InlineKeyboardBuilder().row(cancelButton()).build());
        }

        String text = update.getMessage().getText();
        Long userId = update.getMessage().getFrom().getId();

        int days;
        try {
            days = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("⚠️ Введи целое число (например: 30). Попробуй ещё раз:")
                    .build();
        }

        if (days < 1 || days > 365) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("⚠️ Длительность должна быть от 1 до 365 дней. Попробуй ещё раз:")
                    .build();
        }

        UserEntity user = userService.findByTelegramId(userId);
        WizardContext ctx = parseContext(user);
        ctx.setDurationDays(days);
        saveContext(userId, ctx);

        userService.updateUserState(userId, StateBot.CREATE_MARATHON_DATE_START);

        return SendMessage.builder()
                .chatId(message.getChatId())
                .text(StateBot.CREATE_MARATHON_DATE_START.getMessage())
                .replyMarkup(new InlineKeyboardBuilder().row(cancelButton()).build())
                .build();
    }

    private WizardContext parseContext(UserEntity user) {
        if (user == null || user.getInputContext() == null) {
            return new WizardContext();
        }
        try {
            return objectMapper.readValue(user.getInputContext(), WizardContext.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse wizard context", e);
            return new WizardContext();
        }
    }

    private void saveContext(Long userId, WizardContext ctx) {
        try {
            userService.updateInputContext(userId, objectMapper.writeValueAsString(ctx));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize wizard context for user {}", userId, e);
        }
    }
}
