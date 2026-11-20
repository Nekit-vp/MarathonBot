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
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import static com.nekitvp.marathonbot.handler.manager.create.CreateMarathonInfoHandler.cancelButton;
import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreateMarathonDescriptionHandler extends AbstractHandler {

    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.CREATE_MARATHON_DESCRIPTION;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);

        // Initial display or re-display (edit existing message)
        if (update.hasCallbackQuery()) {
            InlineKeyboardBuilder builder = new InlineKeyboardBuilder();
            builder.row(createButtonByState(StateBot.CREATE_MARATHON_DURATION));
            builder.row(cancelButton());
            return getDefaultMessage(message, builder.build());
        }

        String text = update.getMessage().getText();
        Long userId = update.getMessage().getFrom().getId();

        UserEntity user = userService.findByTelegramId(userId);
        WizardContext ctx = parseContext(user);

        if (ctx.getName() == null) {
            // Context lost — restart wizard
            userService.updateUserState(userId, StateBot.CREATE_MARATHON_NAME);
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Что-то пошло не так. Начнём заново.\n\n" + StateBot.CREATE_MARATHON_NAME.getMessage())
                    .build();
        }

        ctx.setDescription(text != null ? text.trim() : null);
        saveContext(userId, ctx);

        userService.updateUserState(userId, StateBot.CREATE_MARATHON_DURATION);

        return SendMessage.builder()
                .chatId(message.getChatId())
                .text(StateBot.CREATE_MARATHON_DURATION.getMessage())
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
            log.warn("Failed to parse wizard context: {}", user.getInputContext(), e);
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
