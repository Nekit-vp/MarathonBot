package com.nekitvp.marathonbot.handler.manager.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.manager.AbstractManagerHandler;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.service.LetterSender;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Component
@RequiredArgsConstructor
public class ManagerEditDateEndHandler extends AbstractManagerHandler {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final UserService userService;
    private final MarathonRepository marathonRepository;
    private final LetterSender letterSender;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_EDIT_DATE_END;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long telegramUserId = getUserId(update);

        if (update.hasCallbackQuery()) {
            return getDefaultMessage(message, new InlineKeyboardBuilder()
                    .row(backButton()).build());
        }

        String input = message.getText().trim();
        LocalDate date;
        try {
            date = LocalDate.parse(input, FMT);
        } catch (DateTimeParseException e) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("❌ Неверный формат даты. Используй дд.мм.гггг (например: 30.03.2026)")
                    .replyMarkup(new InlineKeyboardBuilder().row(backButton()).build())
                    .build();
        }

        WizardContext ctx = readCtx(telegramUserId, userService, objectMapper);
        Long marathonId = ctx.getSelectedMarathonId();
        if (marathonId == null) return noMarathonMessage(message);

        LocalDate finalDate = date;
        String managerName = userService.getUser(telegramUserId).getEffectiveName();
        marathonRepository.findById(marathonId).ifPresent(m -> {
            if (m.getDateStart() != null && finalDate.isBefore(m.getDateStart().toLocalDate())) {
                // skip — validation handled below
                return;
            }
            m.setDateEnd(finalDate.atTime(LocalTime.of(23, 59, 59)));
            marathonRepository.save(m);
            if (m.getGroupId() != null) {
                letterSender.publishTextEscape(m.getGroupId(),
                        "⚙️ Менеджер " + managerName + " обновил дату финиша марафона: " + finalDate.format(FMT));
            }
        });

        // Re-check after save
        boolean valid = marathonRepository.findById(marathonId)
                .map(m -> m.getDateEnd() != null && m.getDateEnd().toLocalDate().equals(date))
                .orElse(false);

        if (!valid) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("❌ Дата финиша не может быть раньше даты старта.")
                    .replyMarkup(new InlineKeyboardBuilder().row(backButton()).build())
                    .build();
        }

        return SendMessage.builder()
                .chatId(message.getChatId())
                .text("✅ Дата финиша обновлена: " + date.format(FMT))
                .replyMarkup(new InlineKeyboardBuilder().row(backButton()).build())
                .build();
    }

    private InlineKeyboardButton backButton() {
        return InlineKeyboardButton.builder()
                .text("Вернуться к настройкам")
                .callbackData(StateBot.MANAGER_SETTINGS.name())
                .build();
    }

    private SendMessage noMarathonMessage(Message message) {
        return SendMessage.builder().chatId(message.getChatId())
                .text("Марафон не выбран.").build();
    }
}
