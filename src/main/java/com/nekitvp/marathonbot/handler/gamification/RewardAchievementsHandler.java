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

@Component
@RequiredArgsConstructor
public class RewardAchievementsHandler extends AbstractHandler {
    private final UserService userService;
    private final GamificationViewService viewService;

    @Override public StateBot getCurrentState() { return StateBot.REWARD_ACHIEVEMENTS; }
    @Override public StateBot getNextState() { return StateBot.REWARD_ACHIEVEMENTS; }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        RenderedPage page = viewService.getCabinet(userService.getUser(message.getChatId())).renderAchievements(page(message));
        return response(message, page, keyboard(page));
    }

    private static int page(Message message) { return message.getText() != null && message.getText().matches("\\d+") ? Integer.parseInt(message.getText()) : 1; }
    private static InlineKeyboardMarkup keyboard(RenderedPage page) {
        InlineKeyboardBuilder builder = new InlineKeyboardBuilder();
        if (page.totalPages() > 1) builder.row(
                page.page() > 1 ? numeric("⬅️", page.page() - 1) : noop(page.page()),
                page.page() < page.totalPages() ? numeric("➡️", page.page() + 1) : noop(page.page()));
        return builder.row(explicit("⬅️ Награды", StateBot.REWARDS_MAIN)).build();
    }
    private static InlineKeyboardButton numeric(String text, int page) { return InlineKeyboardButton.builder().text(text).callbackData(String.valueOf(page)).build(); }
    private static InlineKeyboardButton noop(int page) { return InlineKeyboardButton.builder().text("·").callbackData(String.valueOf(page)).build(); }
    private static InlineKeyboardButton explicit(String text, StateBot state) { return InlineKeyboardButton.builder().text(text).callbackData(state.name()).build(); }
    private static Object response(Message message, RenderedPage page, InlineKeyboardMarkup keyboard) {
        if (message.hasReplyMarkup()) return EditMessageText.builder().chatId(message.getChatId()).messageId(message.getMessageId()).text(page.text()).parseMode("MarkdownV2").replyMarkup(keyboard).build();
        return SendMessage.builder().chatId(message.getChatId()).text(page.text()).parseMode("MarkdownV2").replyMarkup(keyboard).build();
    }
}
