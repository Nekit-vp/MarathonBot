package com.nekitvp.marathonbot.handler.manager.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.manager.AbstractManagerHandler;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;

import static com.nekitvp.marathonbot.util.TelegramUtil.button;
import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Component
@RequiredArgsConstructor
public class ManagerGoalsSelectUserHandler extends AbstractManagerHandler {

    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_GOALS_SELECT_USER;
    }

    @Override
    public StateBot getNextState() {
        return StateBot.MANAGER_USER_GOALS;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long telegramUserId = getUserId(update);

        WizardContext ctx = readCtx(telegramUserId, userService, objectMapper);
        ctx.setSelectedUserId(null);
        ctx.setSelectedGoalId(null);
        ctx.setPendingDeleteId(null);
        saveCtx(telegramUserId, ctx, userService, objectMapper);

        Long marathonId = ctx.getSelectedMarathonId();
        if (marathonId == null) {
            return getDefaultMessage(message, "Марафон не выбран.",
                    new InlineKeyboardBuilder().row(createButtonByState(StateBot.MANAGER_MAIN)).build());
        }

        List<UserEntity> participants = userService.getUsersByMarathonId(marathonId);

        InlineKeyboardBuilder builder = new InlineKeyboardBuilder();
        for (UserEntity u : participants) {
            String label = u.getEffectiveName()
                    + (u.getTelegramUserName() != null ? " @" + u.getTelegramUserName() : "");
            builder.row(button(label, u.getTelegramId()));
        }
        builder.row(backButton());

        return getDefaultMessage(message, builder.build());
    }

    private InlineKeyboardButton backButton() {
        return InlineKeyboardButton.builder()
                .text("Вернуться к марафону")
                .callbackData(StateBot.MANAGER_DETAIL.name())
                .build();
    }
}
