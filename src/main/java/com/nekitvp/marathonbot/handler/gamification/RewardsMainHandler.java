package com.nekitvp.marathonbot.handler.gamification;

import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.gamification.service.GamificationViewService;
import com.nekitvp.marathonbot.gamification.view.GamificationCabinetView.RenderedPage;
import com.nekitvp.marathonbot.handler.AbstractHandler;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Component
@RequiredArgsConstructor
public class RewardsMainHandler extends AbstractHandler {

    private final UserService userService;
    private final GamificationViewService viewService;

    @Override
    public StateBot getCurrentState() {
        return StateBot.REWARDS_MAIN;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        RenderedPage page = viewService.openCabinet(userService.getUser(message.getChatId())).renderSummary();
        InlineKeyboardMarkup keyboard = new InlineKeyboardBuilder()
                .row(explicit("🏅 Медали", StateBot.REWARD_MEDALS),
                        explicit("🏆 Достижения", StateBot.REWARD_ACHIEVEMENTS))
                .row(explicit("🔥 Серии", StateBot.REWARD_STREAKS), explicit("🗺️ Экспедиция", StateBot.EXPEDITION))
                .row(createButtonByState(StateBot.MARATHON))
                .build();
        return response(message, page.text(), keyboard);
    }

    private static InlineKeyboardButton explicit(String text, StateBot state) {
        return InlineKeyboardButton.builder().text(text).callbackData(state.name()).build();
    }

    private static Object response(Message message, String text, InlineKeyboardMarkup keyboard) {
        if (message.hasReplyMarkup()) {
            return EditMessageText.builder().chatId(message.getChatId()).messageId(message.getMessageId())
                    .text(text).parseMode("MarkdownV2").replyMarkup(keyboard).build();
        }
        return SendMessage.builder().chatId(message.getChatId()).text(text).parseMode("MarkdownV2")
                .replyMarkup(keyboard).build();
    }
}
