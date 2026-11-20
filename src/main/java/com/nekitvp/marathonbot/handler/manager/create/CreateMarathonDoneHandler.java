package com.nekitvp.marathonbot.handler.manager.create;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.AbstractHandler;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.service.LetterSender;
import com.nekitvp.marathonbot.service.MarathonService;
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

import java.time.LocalDate;

import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreateMarathonDoneHandler extends AbstractHandler {

    private final UserService userService;
    private final MarathonService marathonService;
    private final LetterSender letterSender;
    private final ObjectMapper objectMapper;

    @Value("${superadmin.telegram-id:0}")
    private Long superadminTelegramId;

    @Override
    public StateBot getCurrentState() {
        return StateBot.CREATE_MARATHON_DONE;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long userId = update.hasCallbackQuery()
                ? update.getCallbackQuery().getFrom().getId()
                : update.getMessage().getFrom().getId();

        UserEntity user = userService.findByTelegramId(userId);
        WizardContext ctx = parseContext(user);

        if (ctx.getName() == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("❌ Данные не найдены. Начни создание заново.")
                    .replyMarkup(new InlineKeyboardBuilder()
                            .row(createButtonByState(StateBot.CREATE_MARATHON_INFO))
                            .build())
                    .build();
        }

        // Сохраняем данные из контекста до его очистки
        String name = ctx.getName();
        String description = ctx.getDescription();
        LocalDate dateStart = ctx.getDateStart() != null
                ? LocalDate.parse(ctx.getDateStart())
                : LocalDate.now().plusDays(1);
        int durationDays = ctx.getDurationDays() != null ? ctx.getDurationDays() : 30;

        // Очищаем контекст ДО создания марафона — защита от двойного нажатия/повторного запроса
        userService.updateInputContext(userId, null);
        userService.updateUserState(userId, StateBot.MANAGER_MAIN);

        MarathonEntity marathon = marathonService.createMarathon(
                name,
                description,
                dateStart,
                durationDays,
                userId
        );

        // Уведомление суперадмину о создании марафона
        if (superadminTelegramId != null && superadminTelegramId != 0) {
            String creatorName = user != null ? user.getEffectiveName() : "неизвестный";
            letterSender.publishTextEscape(superadminTelegramId,
                    "🆕 Создан новый марафон!\n" +
                    "Название: " + marathon.getName() + "\n" +
                    "Создатель: " + creatorName + " (ID: " + userId + ")\n" +
                    "Старт: " + marathon.getDateStart().toLocalDate() + "\n" +
                    "Финиш: " + marathon.getDateEnd().toLocalDate()
            );
        }

        String bindCode = marathon.getBindCode();
        String text = "🎉 Марафон <b>" + marathon.getName() + "</b> успешно создан!\n\n" +
                "Осталось привязать его к группе. Добавь бота в свою Telegram-группу и отправь там команду:\n\n" +
                "<code>/bind " + bindCode + "</code>\n\n" +
                "👆 Нажми на команду выше, чтобы скопировать.\n\n" +
                "После привязки участники смогут вступить по инвайт-ссылке 🔗";

        InlineKeyboardBuilder builder = new InlineKeyboardBuilder();
        builder.row(createButtonByState(StateBot.MANAGER_MAIN));

        if (update.hasCallbackQuery()) {
            return EditMessageText.builder()
                    .messageId(message.getMessageId())
                    .chatId(message.getChatId())
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(builder.build())
                    .build();
        }
        return SendMessage.builder()
                .chatId(message.getChatId())
                .text(text)
                .parseMode("HTML")
                .replyMarkup(builder.build())
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
}
