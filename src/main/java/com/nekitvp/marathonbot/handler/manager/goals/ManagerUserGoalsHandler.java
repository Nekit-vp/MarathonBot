package com.nekitvp.marathonbot.handler.manager.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.manager.AbstractManagerHandler;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.model.GoalEntity;
import com.nekitvp.marathonbot.repository.GoalRepository;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.List;

import static com.nekitvp.marathonbot.util.TelegramUtil.button;
import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Component
@RequiredArgsConstructor
public class ManagerUserGoalsHandler extends AbstractManagerHandler {

    private final UserService userService;
    private final GoalRepository goalRepository;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_USER_GOALS;
    }

    @Override
    public StateBot getNextState() {
        return StateBot.MANAGER_GOAL_EDIT;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long telegramUserId = getUserId(update);

        WizardContext ctx = readCtx(telegramUserId, userService, objectMapper);

        if (isNumericCallback(message)) {
            Long selectedUserId = extractId(message);
            ctx.setSelectedUserId(selectedUserId);
            ctx.setSelectedGoalId(null);
            ctx.setPendingDeleteId(null);
            saveCtx(telegramUserId, ctx, userService, objectMapper);
        }

        Long selectedUserId = ctx.getSelectedUserId();
        Long marathonId = ctx.getSelectedMarathonId();

        if (selectedUserId == null || marathonId == null) {
            return getDefaultMessage(message, "Участник не выбран.",
                    new InlineKeyboardBuilder()
                            .row(createButtonByState(StateBot.MANAGER_GOALS_SELECT_USER))
                            .build());
        }

        List<GoalEntity> goals = goalRepository.findAllByUserIdAndMarathonIdOrderByPosition(selectedUserId, marathonId);

        StringBuilder text = new StringBuilder("🎯 Цели участника (" + goals.size() + "):\n\n");
        for (GoalEntity goal : goals) {
            String prefix = goal.getPosition() == 0 ? "0. [Отчет] " : goal.getPosition() + ". ";
            text.append(prefix).append(goal.getName()).append("\n");
        }
        text.append("\nНажми на номер цели для управления:");

        InlineKeyboardBuilder builder = new InlineKeyboardBuilder();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (GoalEntity goal : goals) {
            row.add(button(String.valueOf(goal.getPosition()), goal.getId()));
            if (row.size() == 3) {
                builder.row(row.toArray(new InlineKeyboardButton[0]));
                row.clear();
            }
        }
        if (!row.isEmpty()) {
            builder.row(row.toArray(new InlineKeyboardButton[0]));
        }
        builder.row(createButtonByState(StateBot.MANAGER_GOAL_ADD),
                    createButtonByState(StateBot.MANAGER_GOAL_IMPORT));
        builder.row(backButton());

        return getDefaultMessage(message, text.toString(), builder.build());
    }

    private InlineKeyboardButton backButton() {
        return InlineKeyboardButton.builder()
                .text("Вернуться к выбору участника")
                .callbackData(StateBot.MANAGER_GOALS_SELECT_USER.name())
                .build();
    }
}
