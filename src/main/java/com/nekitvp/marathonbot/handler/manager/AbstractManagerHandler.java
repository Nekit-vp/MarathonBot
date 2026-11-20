package com.nekitvp.marathonbot.handler.manager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.handler.AbstractHandler;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

@Slf4j
public abstract class AbstractManagerHandler extends AbstractHandler {

    protected Long getUserId(Update update) {
        return update.hasCallbackQuery()
                ? update.getCallbackQuery().getFrom().getId()
                : update.getMessage().getFrom().getId();
    }

    protected WizardContext readCtx(Long userId, UserService userService, ObjectMapper objectMapper) {
        UserEntity user = userService.findByTelegramId(userId);
        if (user == null || user.getInputContext() == null) {
            return new WizardContext();
        }
        try {
            return objectMapper.readValue(user.getInputContext(), WizardContext.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse WizardContext for user {}", userId, e);
            return new WizardContext();
        }
    }

    protected void saveCtx(Long userId, WizardContext ctx, UserService userService, ObjectMapper objectMapper) {
        try {
            userService.updateInputContext(userId, objectMapper.writeValueAsString(ctx));
        } catch (JsonProcessingException e) {
            log.error("Failed to save WizardContext for user {}", userId, e);
        }
    }

    protected boolean isNumericCallback(Message message) {
        String text = message.getText();
        if (text == null) return false;
        try {
            Long.parseLong(text.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    protected Long extractId(Message message) {
        return Long.parseLong(message.getText().trim());
    }
}
