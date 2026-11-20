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

import static com.nekitvp.marathonbot.handler.manager.create.CreateMarathonInfoHandler.cancelButton;
import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreateMarathonConfirmHandler extends AbstractHandler {

    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.CREATE_MARATHON_CONFIRM;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);

        // Если пришёл текст (не колбэк) — пользователь что-то напечатал на экране подтверждения.
        // Не создаём новое сообщение с кнопкой, чтобы не накапливать дубли «✅ Создать».
        if (!update.hasCallbackQuery()) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Нажми кнопку «✅ Создать» в сообщении выше, чтобы завершить создание марафона.")
                    .build();
        }

        Long userId = update.getCallbackQuery().getFrom().getId();
        UserEntity user = userService.findByTelegramId(userId);
        WizardContext ctx = parseContext(user);

        InlineKeyboardBuilder builder = new InlineKeyboardBuilder();
        builder.row(createButtonByState(StateBot.CREATE_MARATHON_DONE));
        builder.row(cancelButton());

        return SendMessage.builder()
                .chatId(message.getChatId())
                .text(buildConfirmText(ctx))
                .replyMarkup(builder.build())
                .build();
    }

    private String buildConfirmText(WizardContext ctx) {
        if (ctx.getName() == null) {
            return "Данные не найдены. Начни заново с кнопки «Создать марафон ➕».";
        }
        String description = ctx.getDescription() != null ? ctx.getDescription() : "—";
        LocalDate dateStart = ctx.getDateStart() != null ? LocalDate.parse(ctx.getDateStart()) : LocalDate.now().plusDays(1);
        int days = ctx.getDurationDays() != null ? ctx.getDurationDays() : 30;
        LocalDate dateEnd = dateStart.plusDays(days);

        return "Проверь данные марафона:\n\n" +
                "📋 Название: " + ctx.getName() + "\n" +
                "📝 Описание: " + description + "\n" +
                "📅 Старт: " + dateStart.format(DISPLAY_FORMATTER) + "\n" +
                "🏁 Финиш: " + dateEnd.format(DISPLAY_FORMATTER) + " (через " + days + " дней)\n\n" +
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
}
