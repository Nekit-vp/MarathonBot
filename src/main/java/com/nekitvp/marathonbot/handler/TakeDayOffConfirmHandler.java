package com.nekitvp.marathonbot.handler;

import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.service.HistoryService;
import com.nekitvp.marathonbot.service.MarathonService;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.service.LetterSender;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.nekitvp.marathonbot.util.DateTimeUtil.isRestDay;
import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Component
@RequiredArgsConstructor
public class TakeDayOffConfirmHandler extends AbstractHandler {

    private final UserService userService;
    private final HistoryService historyService;
    private final MarathonService marathonService;
    private final LetterSender letterSender;

    @Override
    public StateBot getCurrentState() {
        return StateBot.TAKE_DAY_OFF_CONFIRM;
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

        if (isBeforeStart || isAfterEnd
                || isRestDay(marathon, today)
                || !Boolean.TRUE.equals(marathon.getWeeklyDayOffEnabled())
                || historyService.checkExistHistoryToday(telegramId)
                || historyService.hasTakenDayOffThisWeek(telegramId)) {
            return getDefaultMessage(message,
                    "🌴 Не удалось оформить выходной — условия изменились.",
                    new InlineKeyboardBuilder().row(createButtonByState(StateBot.MARATHON)).build());
        }

        historyService.createDayOff(telegramId);

        if (marathon.getGroupId() != null) {
            letterSender.sendDayOff(user);
        }

        return getDefaultMessage(message,
                "🌴 Выходной оформлен! Сегодня отчёт не потребуется.",
                new InlineKeyboardBuilder().row(createButtonByState(StateBot.MARATHON)).build());
    }
}
