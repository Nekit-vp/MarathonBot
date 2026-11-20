package com.nekitvp.marathonbot.handler.manager.create;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.AbstractHandler;
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
public class CreateMarathonNameHandler extends AbstractHandler {

    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.CREATE_MARATHON_NAME;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);

        // Initial display (button click navigated here — edit existing message)
        if (update.hasCallbackQuery()) {
            InlineKeyboardBuilder kb = new InlineKeyboardBuilder();
            kb.row(cancelButton());
            return getDefaultMessage(message, kb.build());
        }

        String text = update.getMessage().getText();
        Long userId = update.getMessage().getFrom().getId();

        if (text == null || text.isBlank() || text.length() > 100) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("⚠️ Название не должно быть пустым и не длиннее 100 символов.\n\nПопробуй ещё раз:")
                    .build();
        }

        WizardContext ctx = new WizardContext();
        ctx.setName(text.trim());
        saveContext(userId, ctx);

        userService.updateUserState(userId, StateBot.CREATE_MARATHON_DESCRIPTION);

        InlineKeyboardBuilder builder = new InlineKeyboardBuilder();
        builder.row(createButtonByState(StateBot.CREATE_MARATHON_DURATION));
        builder.row(cancelButton());

        return SendMessage.builder()
                .chatId(message.getChatId())
                .text(StateBot.CREATE_MARATHON_DESCRIPTION.getMessage())
                .replyMarkup(builder.build())
                .build();
    }

    private void saveContext(Long userId, WizardContext ctx) {
        try {
            userService.updateInputContext(userId, objectMapper.writeValueAsString(ctx));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize wizard context for user {}", userId, e);
        }
    }
}
