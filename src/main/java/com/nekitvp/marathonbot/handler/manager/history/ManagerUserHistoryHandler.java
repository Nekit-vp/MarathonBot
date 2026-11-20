package com.nekitvp.marathonbot.handler.manager.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.manager.AbstractManagerHandler;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.model.HistoryEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.service.HistoryService;
import com.nekitvp.marathonbot.service.LetterSender;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.nekitvp.marathonbot.util.TelegramUtil.button;

@Component
@RequiredArgsConstructor
public class ManagerUserHistoryHandler extends AbstractManagerHandler {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM");
    private static final int BUTTONS_PER_ROW = 5;

    private final UserService userService;
    private final HistoryService historyService;
    private final MarathonRepository marathonRepository;
    private final LetterSender letterSender;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_USER_HISTORY;
    }

    @Override
    public StateBot getNextState() {
        return StateBot.MANAGER_USER_HISTORY;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long telegramUserId = getUserId(update);

        WizardContext ctx = readCtx(telegramUserId, userService, objectMapper);

        if (isNumericCallback(message)) {
            Long incomingId = extractId(message);

            if (ctx.getSelectedDate() == null) {
                // Incoming is epochDay — save date
                LocalDate date = LocalDate.ofEpochDay(incomingId);
                ctx.setSelectedDate(date.toString());
                saveCtx(telegramUserId, ctx, userService, objectMapper);
            } else {
                // Incoming is historyId — toggle status
                Long historyId = incomingId;
                LocalDate date = LocalDate.parse(ctx.getSelectedDate());
                List<HistoryEntity> histories = historyService.getHistoryForDate(ctx.getSelectedUserId(), date);
                histories.stream()
                        .filter(h -> h.getId().equals(historyId))
                        .findFirst()
                        .ifPresent(h -> {
                            Boolean oldStatus = h.getDone();
                            Boolean newStatus = toggleDone(oldStatus);
                            historyService.updateHistoryStatus(historyId, newStatus);
                            notifyGroup(ctx, telegramUserId, h, oldStatus, newStatus, date);
                        });
            }
        }

        String selectedDate = ctx.getSelectedDate();
        if (selectedDate == null || ctx.getSelectedUserId() == null) {
            return getDefaultMessage(message, "Дата не выбрана.",
                    new InlineKeyboardBuilder().row(backToDatesButton()).build());
        }

        LocalDate date = LocalDate.parse(selectedDate);
        List<HistoryEntity> histories = historyService.getHistoryForDate(ctx.getSelectedUserId(), date);
        histories.sort(Comparator.comparingInt(h -> h.getGoal().getPosition() == 0
                ? Integer.MAX_VALUE : h.getGoal().getPosition()));

        return buildDayReport(message, histories, date);
    }

    private Object buildDayReport(Message message, List<HistoryEntity> histories, LocalDate date) {
        if (histories.isEmpty()) {
            return getDefaultMessage(message, "📊 Отчёт за " + date.format(DATE_FMT) + ":\n\nЗаписей нет.",
                    new InlineKeyboardBuilder().row(backToDatesButton()).build());
        }

        StringBuilder text = new StringBuilder();
        text.append("📊 Отчёт за ").append(date.format(DATE_FMT)).append(":\n\n");

        int idx = 1;
        for (HistoryEntity h : histories) {
            String icon = statusIcon(h.getDone());
            text.append(idx).append(". ").append(icon).append(" ").append(h.getGoal().getName()).append("\n");
            idx++;
        }

        InlineKeyboardBuilder builder = new InlineKeyboardBuilder();
        List<InlineKeyboardButton> row = new ArrayList<>();
        idx = 1;
        for (HistoryEntity h : histories) {
            String icon = statusIcon(h.getDone());
            row.add(button(idx + icon, h.getId()));
            if (row.size() == BUTTONS_PER_ROW) {
                builder.row(row.toArray(new InlineKeyboardButton[0]));
                row.clear();
            }
            idx++;
        }
        if (!row.isEmpty()) {
            builder.row(row.toArray(new InlineKeyboardButton[0]));
        }
        builder.row(backToDatesButton());

        return getDefaultMessage(message, text.toString(), builder.build());
    }

    private void notifyGroup(WizardContext ctx, Long telegramUserId, HistoryEntity h,
                             Boolean oldStatus, Boolean newStatus, LocalDate date) {
        Long marathonId = ctx.getSelectedMarathonId();
        if (marathonId == null) return;

        marathonRepository.findById(marathonId).ifPresent(m -> {
            if (m.getGroupId() == null) return;

            String managerName = userService.findByTelegramId(telegramUserId) != null
                    ? userService.findByTelegramId(telegramUserId).getEffectiveName() : "Менеджер";
            String participantName = userService.findByTelegramId(ctx.getSelectedUserId()) != null
                    ? userService.findByTelegramId(ctx.getSelectedUserId()).getEffectiveName() : "Участник";

            String oldIcon = statusIcon(oldStatus);
            String newIcon = statusIcon(newStatus);
            String dateStr = date.format(DATE_FMT);

            letterSender.publishTextEscape(m.getGroupId(),
                    "📋 Менеджер " + managerName + " изменил отчёт " + participantName
                            + " за " + dateStr + ":\n«" + h.getGoal().getName() + "»: "
                            + oldIcon + " → " + newIcon);
        });
    }

    private String statusIcon(Boolean done) {
        if (Boolean.TRUE.equals(done)) return "✅";
        if (Boolean.FALSE.equals(done)) return "❌";
        return "⏳";
    }

    private Boolean toggleDone(Boolean current) {
        if (Boolean.TRUE.equals(current)) return false;
        return true;
    }

    private InlineKeyboardButton backToDatesButton() {
        return InlineKeyboardButton.builder()
                .text("⬅️ К датам")
                .callbackData(StateBot.MANAGER_HISTORY_SELECT_DATE.name())
                .build();
    }
}
