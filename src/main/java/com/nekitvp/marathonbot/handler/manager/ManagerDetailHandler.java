package com.nekitvp.marathonbot.handler.manager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.AbstractHandler;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Slf4j
@Component
@RequiredArgsConstructor
public class ManagerDetailHandler extends AbstractHandler {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final UserService userService;
    private final MarathonRepository marathonRepository;
    private final ObjectMapper objectMapper;

    @Value("${bot.username}")
    private String botUsername;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_DETAIL;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long userId = update.hasCallbackQuery()
                ? update.getCallbackQuery().getFrom().getId()
                : update.getMessage().getFrom().getId();

        // Try to get marathon ID from message text (set by UpdateHandler for "выбран id" flow)
        Long marathonId = parseMarathonId(message.getText(), userId);

        if (marathonId == null) {
            return buildResponse(message, "Марафон не найден.",
                    new InlineKeyboardBuilder().row(createButtonByState(StateBot.MANAGER_MAIN)).build());
        }

        // Save selected marathon ID to context
        saveSelectedMarathon(userId, marathonId);

        Optional<MarathonEntity> opt = marathonRepository.findById(marathonId);
        if (opt.isEmpty()) {
            return buildResponse(message, "Марафон не найден.",
                    new InlineKeyboardBuilder().row(createButtonByState(StateBot.MANAGER_MAIN)).build());
        }

        MarathonEntity marathon = opt.get();
        List<UserEntity> participants = userService.getUsersByMarathonId(marathonId);

        String detailText = buildDetailText(marathon, participants.size());

        InlineKeyboardBuilder builder = new InlineKeyboardBuilder();
        builder.row(createButtonByState(StateBot.MANAGER_USERS),
                    createButtonByState(StateBot.MANAGER_GOALS_SELECT_USER));
        builder.row(createButtonByState(StateBot.MANAGER_SEND_MESSAGE));
        builder.row(createButtonByState(StateBot.MANAGER_HISTORY_SELECT_USER),
                    createButtonByState(StateBot.MANAGER_SETTINGS));
        builder.row(backToListButton());

        return buildResponse(message, detailText, builder.build());
    }

    private Object buildResponse(Message message, String text, InlineKeyboardMarkup keyboard) {
        if (message.hasReplyMarkup()) {
            return EditMessageText.builder()
                    .messageId(message.getMessageId())
                    .chatId(message.getChatId())
                    .text(text)
                    .replyMarkup(keyboard)
                    .disableWebPagePreview(true)
                    .build();
        } else {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text(text)
                    .replyMarkup(keyboard)
                    .disableWebPagePreview(true)
                    .build();
        }
    }

    private String buildDetailText(MarathonEntity marathon, int participantCount) {
        String startStr = marathon.getDateStart() != null
                ? marathon.getDateStart().format(DATE_FORMATTER) : "не указан";
        String endStr = marathon.getDateEnd() != null
                ? marathon.getDateEnd().format(DATE_FORMATTER) : "не указан";
        String chatInfo = marathon.getGroupId() != null
                ? "привязан (id: " + marathon.getGroupId() + ")" : "не привязан";
        String bindInfo;
        if (marathon.getBindCode() != null && marathon.getBindCodeExpiry() != null
                && marathon.getBindCodeExpiry().isAfter(LocalDateTime.now())) {
            bindInfo = marathon.getBindCode() + " (до " + marathon.getBindCodeExpiry().format(
                    DateTimeFormatter.ofPattern("HH:mm dd.MM")) + ")";
        } else {
            bindInfo = "нет активного кода";
        }
        String inviteLink = marathon.getGroupId() != null
                ? "https://t.me/" + botUsername + "?start=join_" + marathon.getId()
                : "сначала привяжи чат через /bind";

        return "📋 " + marathon.getName() + " [" + marathon.getStatus().getDisplayName() + "]\n\n" +
                "📅 Старт: " + startStr + "\n" +
                "🏁 Финиш: " + endStr + "\n" +
                "👥 Участников: " + participantCount + "\n" +
                "🔗 Чат: " + chatInfo + "\n" +
                "🔑 Bind-код: " + bindInfo + "\n" +
                "🔗 Инвайт: " + inviteLink;
    }

    private Long parseMarathonId(String text, Long userId) {
        // First try to parse from message text
        if (text != null) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        // Fall back to stored selected marathon ID in user's context
        UserEntity user = userService.findByTelegramId(userId);
        if (user != null && user.getInputContext() != null) {
            try {
                WizardContext ctx = objectMapper.readValue(user.getInputContext(), WizardContext.class);
                return ctx.getSelectedMarathonId();
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse wizard context for selectedMarathonId", e);
            }
        }
        return null;
    }

    private org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton backToListButton() {
        return org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                .text("Вернуться к списку марафонов")
                .callbackData(StateBot.MANAGER_MAIN.name())
                .build();
    }

    private void saveSelectedMarathon(Long userId, Long marathonId) {
        WizardContext ctx = new WizardContext();
        ctx.setSelectedMarathonId(marathonId);
        try {
            userService.updateInputContext(userId, objectMapper.writeValueAsString(ctx));
        } catch (JsonProcessingException e) {
            log.error("Failed to save selectedMarathonId for user {}", userId, e);
        }
    }
}
