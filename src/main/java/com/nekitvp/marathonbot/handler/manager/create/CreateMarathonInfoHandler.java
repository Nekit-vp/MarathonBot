package com.nekitvp.marathonbot.handler.manager.create;

import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.AbstractHandler;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Component
public class CreateMarathonInfoHandler extends AbstractHandler {

    @Override
    public StateBot getCurrentState() {
        return StateBot.CREATE_MARATHON_INFO;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);

        InlineKeyboardBuilder builder = new InlineKeyboardBuilder();
        builder.row(
                createButtonByState(StateBot.CREATE_MARATHON_NAME),
                cancelButton()
        );

        return getDefaultMessage(message, builder.build());
    }

    static InlineKeyboardButton cancelButton() {
        return InlineKeyboardButton.builder()
                .text("Отмена ❌")
                .callbackData(StateBot.MANAGER_MAIN.name())
                .build();
    }
}
