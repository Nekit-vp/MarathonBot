package com.nekitvp.marathonbot.handler.manager.users;

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
public class ManagerUsersHandler extends AbstractManagerHandler {

    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_USERS;
    }

    @Override
    public StateBot getNextState() {
        return StateBot.MANAGER_USER_DETAIL;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long userId = getUserId(update);

        WizardContext ctx = readCtx(userId, userService, objectMapper);
        ctx.setSelectedUserId(null);
        ctx.setPendingDeleteId(null);
        saveCtx(userId, ctx, userService, objectMapper);

        Long marathonId = ctx.getSelectedMarathonId();
        if (marathonId == null) {
            return getDefaultMessage(message, "Марафон не выбран.",
                    new InlineKeyboardBuilder()
                            .row(createButtonByState(StateBot.MANAGER_MAIN))
                            .build());
        }

        List<UserEntity> participants = userService.getUsersByMarathonId(marathonId);

        InlineKeyboardBuilder builder = new InlineKeyboardBuilder();
        for (UserEntity u : participants) {
            String label = u.getEffectiveName()
                    + (u.getTelegramUserName() != null ? " @" + u.getTelegramUserName() : "");
            builder.row(button(label, u.getTelegramId()));
        }
        builder.row(createButtonByState(StateBot.MANAGER_ADD_USER));
        builder.row(backButton());

        String text = "👥 Участники марафона (" + participants.size() + "):";
        return getDefaultMessage(message, text, builder.build());
    }

    private InlineKeyboardButton backButton() {
        return InlineKeyboardButton.builder()
                .text("Вернуться к марафону")
                .callbackData(StateBot.MANAGER_DETAIL.name())
                .build();
    }
}
