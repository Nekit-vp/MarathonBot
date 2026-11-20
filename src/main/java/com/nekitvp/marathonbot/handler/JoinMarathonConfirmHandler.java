package com.nekitvp.marathonbot.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.service.MarathonService;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Slf4j
@Component
@RequiredArgsConstructor
public class JoinMarathonConfirmHandler extends AbstractHandler {

    private final UserService userService;
    private final MarathonService marathonService;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.JOIN_MARATHON_CONFIRM;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long userId = update.hasCallbackQuery()
                ? update.getCallbackQuery().getFrom().getId()
                : update.getMessage().getFrom().getId();

        // Текстовое сообщение в этом состоянии — напоминаем нажать кнопку
        if (!update.hasCallbackQuery()) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Нажми «✅ Да, вступить» для подтверждения или «Мой Марафон» для отмены.")
                    .build();
        }

        UserEntity user = userService.findByTelegramId(userId);
        WizardContext ctx = parseContext(user);
        Long pendingId = ctx.getPendingJoinMarathonId();

        if (pendingId == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("❌ Нет ожидающего вступления. Перейди по инвайт-ссылке снова.")
                    .replyMarkup(new InlineKeyboardBuilder()
                            .row(createButtonByState(StateBot.MARATHON))
                            .build())
                    .build();
        }

        // Очищаем контекст до вступления — защита от двойного нажатия
        userService.updateInputContext(userId, null);

        // Перезагружаем user; handleJoin проведёт подтверждённую смену через общий membership-aware путь.
        UserEntity freshUser = userService.getUser(userId);
        String result = marathonService.handleJoin(freshUser, pendingId);

        userService.updateUserState(userId, StateBot.MARATHON);

        return SendMessage.builder()
                .chatId(message.getChatId())
                .text(result)
                .replyMarkup(new InlineKeyboardBuilder()
                        .row(createButtonByState(StateBot.MARATHON))
                        .build())
                .build();
    }

    private WizardContext parseContext(UserEntity user) {
        if (user == null || user.getInputContext() == null) {
            return new WizardContext();
        }
        try {
            return objectMapper.readValue(user.getInputContext(), WizardContext.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse join context for user", e);
            return new WizardContext();
        }
    }
}
