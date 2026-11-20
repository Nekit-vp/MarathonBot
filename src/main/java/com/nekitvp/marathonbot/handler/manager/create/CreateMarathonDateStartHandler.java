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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import static com.nekitvp.marathonbot.handler.manager.create.CreateMarathonInfoHandler.cancelButton;
import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreateMarathonDateStartHandler extends AbstractHandler {

    private static final DateTimeFormatter INPUT_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.CREATE_MARATHON_DATE_START;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);

        if (update.hasCallbackQuery()) {
            return getDefaultMessage(message,
                    new InlineKeyboardBuilder().row(cancelButton()).build());
        }

        String text = update.getMessage().getText();
        Long userId = update.getMessage().getFrom().getId();

        LocalDate dateStart;
        try {
            dateStart = LocalDate.parse(text.trim(), INPUT_FORMATTER);
        } catch (DateTimeParseException e) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("⚠️ Неверный формат даты. Введи дату в формате дд.мм.гггг\n(например: 01.03.2026):")
                    .build();
        }

        if (dateStart.isBefore(LocalDate.now())) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("⚠️ Дата старта не может быть в прошлом. Введи будущую дату:")
                    .build();
        }

        UserEntity user = userService.findByTelegramId(userId);
        WizardContext ctx = parseContext(user);
        ctx.setDateStart(dateStart.toString()); // ISO format yyyy-MM-dd
        saveContext(userId, ctx);

        userService.updateUserState(userId, StateBot.CREATE_MARATHON_CONFIRM);

        // Build confirm screen
        LocalDate dateEnd = dateStart.plusDays(ctx.getDurationDays() != null ? ctx.getDurationDays() : 30);
        String confirmText = buildConfirmText(ctx, dateStart, dateEnd);

        InlineKeyboardBuilder builder = new InlineKeyboardBuilder();
        builder.row(createButtonByState(StateBot.CREATE_MARATHON_DONE));
        builder.row(cancelButton());

        return SendMessage.builder()
                .chatId(message.getChatId())
                .text(confirmText)
                .replyMarkup(builder.build())
                .build();
    }

    private String buildConfirmText(WizardContext ctx, LocalDate dateStart, LocalDate dateEnd) {
        String description = ctx.getDescription() != null ? ctx.getDescription() : "—";
        return "Проверь данные марафона:\n\n" +
                "📋 Название: " + ctx.getName() + "\n" +
                "📝 Описание: " + description + "\n" +
                "📅 Старт: " + dateStart.format(DISPLAY_FORMATTER) + "\n" +
                "🏁 Финиш: " + dateEnd.format(DISPLAY_FORMATTER) + " (через " + ctx.getDurationDays() + " дней)\n\n" +
                "Всё верно?";
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
