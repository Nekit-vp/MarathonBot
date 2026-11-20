package com.nekitvp.marathonbot.handler;

import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.service.HistoryService;
import com.nekitvp.marathonbot.service.MarathonService;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static com.nekitvp.marathonbot.util.DateTimeUtil.isRestDay;
import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Component
@RequiredArgsConstructor
public class TakeDayOffHandler extends AbstractHandler {

    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd.MM");

    private final UserService userService;
    private final HistoryService historyService;
    private final MarathonService marathonService;

    @Override
    public StateBot getCurrentState() {
        return StateBot.TAKE_DAY_OFF;
    }

    @Override
    public Object handle(Update update) {
        var message = getMessage(update);
        Long telegramId = message.getChatId();

        UserEntity user = userService.getUser(telegramId);
        MarathonEntity marathon = user.getMarathon();
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        boolean isBeforeStart = now.isBefore(marathon.getDateStart());
        boolean isAfterEnd = now.isAfter(marathon.getDateEnd());

        if (isBeforeStart || isAfterEnd) {
            return getDefaultMessage(message,
                    "🌴 Выходной недоступен — марафон не активен.",
                    new InlineKeyboardBuilder().row(createButtonByState(StateBot.MARATHON)).build());
        }

        if (isRestDay(marathon, today)) {
            return getDefaultMessage(message,
                    "🌴 Сегодня уже выходной по расписанию марафона.",
                    new InlineKeyboardBuilder().row(createButtonByState(StateBot.MARATHON)).build());
        }

        if (!Boolean.TRUE.equals(marathon.getWeeklyDayOffEnabled())) {
            return getDefaultMessage(message,
                    "🌴 Еженедельный выходной не включён в этом марафоне.",
                    new InlineKeyboardBuilder().row(createButtonByState(StateBot.MARATHON)).build());
        }

        if (historyService.checkExistHistoryToday(telegramId)) {
            return getDefaultMessage(message,
                    "🌴 Сегодня уже есть отчёт — выходной нельзя взять.",
                    new InlineKeyboardBuilder().row(createButtonByState(StateBot.MARATHON)).build());
        }

        if (historyService.hasTakenDayOffThisWeek(telegramId)) {
            return getDefaultMessage(message,
                    "🌴 Ты уже брал выходной на этой неделе.",
                    new InlineKeyboardBuilder().row(createButtonByState(StateBot.MARATHON)).build());
        }

        LocalDate monday = today.with(DayOfWeek.MONDAY);
        LocalDate sunday = today.with(DayOfWeek.SUNDAY);
        String weekRange = monday.format(SHORT_DATE) + " — " + sunday.format(SHORT_DATE) + "." + sunday.getYear();

        String text = "🌴 Взять выходной на сегодня?\n\n" +
                "Неделя: " + weekRange + "\n" +
                "У тебя есть 1 выходной за неделю.\n" +
                "В этот день отчёт не потребуется.";

        InlineKeyboardButton confirmButton = InlineKeyboardButton.builder()
                .text("✅ Да, взять выходной")
                .callbackData(StateBot.TAKE_DAY_OFF_CONFIRM.name())
                .build();

        var keyboard = new InlineKeyboardBuilder()
                .row(confirmButton)
                .row(createButtonByState(StateBot.MARATHON))
                .build();

        return getDefaultMessage(message, text, keyboard);
    }
}
