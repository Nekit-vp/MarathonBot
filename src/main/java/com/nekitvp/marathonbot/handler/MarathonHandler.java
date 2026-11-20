package com.nekitvp.marathonbot.handler;

import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.gamification.service.GamificationViewService;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.service.HistoryService;
import com.nekitvp.marathonbot.service.MarathonService;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import static com.nekitvp.marathonbot.enumBot.StateBot.*;
import static com.nekitvp.marathonbot.util.Constant.NOT_FOUND_MARATHON;
import static com.nekitvp.marathonbot.util.DateTimeUtil.isRestDay;
import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Component
@RequiredArgsConstructor
public class MarathonHandler extends AbstractHandler {

    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy - HH:mm");
    private static final DateTimeFormatter SHORT_DATE_FMT = DateTimeFormatter.ofPattern("dd.MM");

    private final UserService userService;
    private final HistoryService historyService;
    private final MarathonService marathonService;
    private final GamificationViewService gamificationViewService;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MARATHON;
    }

    @Override
    public Object handle(Update update) {
        var message = getMessage(update);

        // проверка на существование марафона
        if (!userService.userHasAnyMarathon(message.getChatId())) {
            userService.updateUserState(message.getChatId(), START);
            var noMarathonKeyboard = new InlineKeyboardBuilder()
                    .row(createButtonByState(CREATE_MARATHON_INFO))
                    .row(createButtonByState(START))
                    .build();
            return getDefaultMessage(message,
                    "Ты пока не состоишь в марафоне! 🏃\n\nСоздай свой или попроси организатора поделиться инвайт-ссылкой.",
                    noMarathonKeyboard);
        }

        var user = userService.getUser(message.getChatId());
        var marathon = user.getMarathon();
        int freeCount = marathon.getFreeFailCount();

        boolean existsReportToday = historyService.checkExistHistoryToday(user.getTelegramId());
        boolean existsReportYesterday = !historyService.checkExistNullHistoryYesterday(user.getTelegramId());

        var failCount = historyService.getCountFailByUserInMarathon(user);
        String failCountString;
        String resultCash;
        LocalDateTime todayTime = LocalDateTime.now();
        LocalDate todayDay = todayTime.toLocalDate();

        boolean restDay = isRestDay(marathon, todayDay);
        boolean restDayYesterday = isRestDay(marathon, todayDay.minusDays(1));
        boolean isBeforeStart = todayTime.isBefore(marathon.getDateStart());
        boolean isAfterEnd = todayTime.isAfter(marathon.getDateEnd());
        boolean takenDayOffThisWeek = Boolean.TRUE.equals(marathon.getWeeklyDayOffEnabled())
                && historyService.hasTakenDayOffThisWeek(user.getTelegramId());

        if (Objects.equals(failCount.getFirst(), failCount.getSecond())) {
            failCountString = failCount.getFirst().toString();
            long cash = calculateCash(failCount.getFirst(), freeCount, marathon.getPenaltyAmount());
            resultCash = String.valueOf(cash);
        } else {
            failCountString = String.format("%d (%d)", failCount.getFirst(), failCount.getSecond());
            long cash = calculateCash(failCount.getFirst(), freeCount, marathon.getPenaltyAmount());
            long cashMaybe = calculateCash(failCount.getSecond(), freeCount, marathon.getPenaltyAmount());
            resultCash = String.format("%s (%s)", cash, cashMaybe);
        }

        var keyboard = new InlineKeyboardBuilder()
                .row(createButtonByState(GOAL), createButtonByState(FAIL_GOAL));
        if (gamificationViewService.hasGameState(marathon.getId())) {
            keyboard.row(createButtonByState(REWARDS_MAIN));
        }

        String statusText;

        if (isAfterEnd) {
            statusText = "🏁 Марафон окончен! Спасибо за участие! 🏆";
        } else if (isBeforeStart) {
            statusText = "Марафон скоро начнётся! 🏁";
        } else if (restDay) {
            statusText = "Сегодня выходной от марафона 🌿 Отдых без отчёта!";
        } else {
            if (existsReportToday) {
                keyboard.row(createButtonByState(DELETE_REPORT));
            } else {
                keyboard.row(createButtonByState(REPORT));
            }
            if (!existsReportYesterday) {
                keyboard.row(createButtonByState(REPORT_YESTERDAY));
            }

            if (Boolean.TRUE.equals(marathon.getWeeklyDayOffEnabled())
                    && !existsReportToday
                    && !takenDayOffThisWeek) {
                keyboard.row(createButtonByState(TAKE_DAY_OFF));
            }

            statusText = getStatusReport(user, existsReportToday, existsReportYesterday, restDayYesterday, todayDay,
                    marathon.getWeeklyDayOffEnabled(), takenDayOffThisWeek, todayDay);
        }

        keyboard.row(createButtonByState(START));

        return getDefaultMessage(message, keyboard.build(),
                user.getEffectiveName(),
                marathon.getName(),
                marathon.getDateStart().format(DATE_TIME_FMT),
                marathon.getDateEnd().format(DATE_TIME_FMT),
                freeCount,
                failCountString,
                resultCash,
                statusText
        );
    }

    private String getStatusReport(UserEntity user, boolean existsReportToday,
                                   boolean existsReportYesterday, boolean restDayYesterday, LocalDate today,
                                   Boolean weeklyDayOffEnabled, boolean takenDayOff, LocalDate todayDate) {
        var marathon = user.getMarathon();

        String todayStatus = "Отчет за сегодня: " + (existsReportToday ? "🟢" : "🔴");
        String result;
        if (today.equals(marathon.getDateStart().toLocalDate()) || restDayYesterday) {
            result = todayStatus;
        } else {
            String yesterdayStatus = "║ Отчет за вчера: " + (existsReportYesterday ? "🟢" : "🔴");
            result = todayStatus + "\n" + yesterdayStatus;
        }

        if (Boolean.TRUE.equals(weeklyDayOffEnabled)) {
            LocalDate monday = todayDate.with(DayOfWeek.MONDAY);
            LocalDate sunday = todayDate.with(DayOfWeek.SUNDAY);
            String weekRange = monday.format(SHORT_DATE_FMT) + " — " + sunday.format(SHORT_DATE_FMT);
            String dayOffStatus = takenDayOff ? "✅ Взят" : "доступен";
            result += "\n🌴 Выходной (" + weekRange + "): " + dayOffStatus;
        }

        return result;
    }

    private long calculateCash(long fails, int freeCount, int penaltyAmount) {
        return fails > freeCount ? (fails - freeCount) * penaltyAmount : 0L;
    }
}
