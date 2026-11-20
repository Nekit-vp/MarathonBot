package com.nekitvp.marathonbot.handler.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.bot.DeliveryWithFeedback;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.Optional;

import static com.nekitvp.marathonbot.util.MarkdownUtil.escape;

@Component
@RequiredArgsConstructor
public class ManagerSendMessageHandler extends AbstractManagerHandler {

    private final UserService userService;
    private final MarathonRepository marathonRepository;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_SEND_MESSAGE;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long telegramUserId = getUserId(update);

        if (update.hasCallbackQuery()) {
            // Show prompt to type message
            return getDefaultMessage(message, new InlineKeyboardBuilder().row(backButton()).build());
        }

        // Text message — send to group chat
        WizardContext ctx = readCtx(telegramUserId, userService, objectMapper);
        Long marathonId = ctx.getSelectedMarathonId();
        if (marathonId == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Марафон не выбран.")
                    .replyMarkup(new InlineKeyboardBuilder().row(backButton()).build())
                    .build();
        }

        Optional<MarathonEntity> opt = marathonRepository.findById(marathonId);
        if (opt.isEmpty()) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Марафон не найден.")
                    .replyMarkup(new InlineKeyboardBuilder().row(backButton()).build())
                    .build();
        }

        MarathonEntity marathon = opt.get();
        if (marathon.getGroupId() == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("❌ Чат марафона не привязан. Сначала привяжи чат через /bind.")
                    .replyMarkup(new InlineKeyboardBuilder().row(backButton()).build())
                    .build();
        }

        String text = message.getText().trim();
        if (text.isEmpty()) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("❌ Сообщение не может быть пустым.")
                    .replyMarkup(new InlineKeyboardBuilder().row(backButton()).build())
                    .build();
        }

        SendMessage delivery = SendMessage.builder()
                .chatId(marathon.getGroupId())
                .text(escape(text))
                .parseMode("MarkdownV2")
                .build();

        InlineKeyboardMarkup keyboard = new InlineKeyboardBuilder().row(backButton()).build();
        SendMessage successFeedback = SendMessage.builder()
                .chatId(message.getChatId())
                .text("✅ Сообщение отправлено в чат марафона «" + marathon.getName() + "».")
                .replyMarkup(keyboard)
                .build();
        SendMessage failureFeedback = SendMessage.builder()
                .chatId(message.getChatId())
                .text("❌ Не удалось отправить сообщение в чат марафона «" + marathon.getName()
                        + "». Попробуй ещё раз.")
                .replyMarkup(keyboard)
                .build();

        return new DeliveryWithFeedback(delivery, successFeedback, failureFeedback);
    }

    private InlineKeyboardButton backButton() {
        return InlineKeyboardButton.builder()
                .text("Вернуться к марафону")
                .callbackData(StateBot.MANAGER_DETAIL.name())
                .build();
    }
}
