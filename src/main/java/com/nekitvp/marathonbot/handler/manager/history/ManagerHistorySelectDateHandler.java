package com.nekitvp.marathonbot.handler.manager.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.manager.AbstractManagerHandler;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
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
import java.util.List;

import static com.nekitvp.marathonbot.util.TelegramUtil.button;

@Component
@RequiredArgsConstructor
public class ManagerHistorySelectDateHandler extends AbstractManagerHandler {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM");
    private static final int DATES_PER_ROW = 4;

    private final UserService userService;
    private final MarathonRepository marathonRepository;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_HISTORY_SELECT_DATE;
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
            ctx.setSelectedUserId(incomingId);
        }

        ctx.setSelectedDate(null);
        saveCtx(telegramUserId, ctx, userService, objectMapper);

        Long selectedUserId = ctx.getSelectedUserId();
        if (selectedUserId == null) {
            return getDefaultMessage(message, "Участник не выбран.",
                    new InlineKeyboardBuilder().row(backToUsersButton()).build());
        }

        Long marathonId = ctx.getSelectedMarathonId();
        if (marathonId == null) {
            return getDefaultMessage(message, "Марафон не выбран.",
                    new InlineKeyboardBuilder().row(backToUsersButton()).build());
        }

        MarathonEntity marathon = marathonRepository.findById(marathonId).orElse(null);
        if (marathon == null) {
            return getDefaultMessage(message, "Марафон не найден.",
                    new InlineKeyboardBuilder().row(backToUsersButton()).build());
        }

        UserEntity participant = userService.findByTelegramId(selectedUserId);
        String participantName = participant != null ? participant.getEffectiveName() : "Участник";

        LocalDate start = marathon.getDateStart().toLocalDate();
        LocalDate end = marathon.getDateEnd().toLocalDate();
        LocalDate today = LocalDate.now();
        if (end.isAfter(today)) {
            end = today;
        }

        // Build date buttons from newest to oldest
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = end; !d.isBefore(start); d = d.minusDays(1)) {
            dates.add(d);
        }

        InlineKeyboardBuilder builder = new InlineKeyboardBuilder();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (LocalDate d : dates) {
            row.add(button(d.format(DATE_FMT), d.toEpochDay()));
            if (row.size() == DATES_PER_ROW) {
                builder.row(row.toArray(new InlineKeyboardButton[0]));
                row.clear();
            }
        }
        if (!row.isEmpty()) {
            builder.row(row.toArray(new InlineKeyboardButton[0]));
        }
        builder.row(backToUsersButton());

        String text = "📊 Отчёты " + participantName + "\nВыбери дату:";
        return getDefaultMessage(message, text, builder.build());
    }

    private InlineKeyboardButton backToUsersButton() {
        return InlineKeyboardButton.builder()
                .text("⬅️ К участникам")
                .callbackData(StateBot.MANAGER_HISTORY_SELECT_USER.name())
                .build();
    }
}
