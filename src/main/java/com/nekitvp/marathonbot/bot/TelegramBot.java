package com.nekitvp.marathonbot.bot;

import com.nekitvp.marathonbot.service.MarathonService;
import com.nekitvp.marathonbot.service.UpdateHandler;
import com.nekitvp.marathonbot.gamification.event.GamificationDeliveryResultEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.polls.SendPoll;
import org.telegram.telegrambots.meta.api.methods.send.*;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Objects;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static com.nekitvp.marathonbot.log.Constant.ERROR_WITH_SENDING_MESSAGE;


@Component
@Slf4j
public class TelegramBot extends TelegramLongPollingBot {

    private final UpdateHandler updateHandler;
    private final MarathonService marathonService;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public TelegramBot(
            UpdateHandler updateHandler,
            MarathonService marathonService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.updateHandler = updateHandler;
        this.marathonService = marathonService;
        this.eventPublisher = eventPublisher;
    }

    @Value("${bot.token}")
    private String token;
    @Value("${bot.name}")
    private String botName;

    @Override
    public String getBotUsername() {
        return botName;
    }

    @Override
    public String getBotToken() {
        return token;
    }

    @Override
    public void onUpdateReceived(Update update) {

        if (Objects.nonNull(update.getMyChatMember())) {
            String name = update.getMyChatMember().getChat().getTitle();
            Long chatId = update.getMyChatMember().getChat().getId();
            if (update.getMyChatMember().getNewChatMember().getStatus().equalsIgnoreCase("left")) {
                log.info("Бот исключен из чата - {} код чата: {}", name, chatId);
                marathonService.leftMarathon(chatId);
            } else {
                log.info("Бот добавлен в чат {}, код чата: {}", name, chatId);
            }
            return;
        }

        // Handle /bind command from groups before badMessage filter
        if (update.hasMessage() && !update.getMessage().isUserMessage()
                && update.getMessage().hasText()) {
            String msgText = update.getMessage().getText();
            if (msgText != null && msgText.startsWith("/bind")) {
                Long chatId = update.getMessage().getChatId();
                Long userId = update.getMessage().getFrom().getId();
                marathonService.handleBindCommand(chatId, userId, msgText);
                return;
            }
        }

        if (badMessage(update)) {
            return;
        }

        Object message = updateHandler.handleMessage(update);

        if (Objects.isNull(message)) {
            return;
        }

        try {
            switch (message) {
                case EditMessageText editMessageText -> execute(editMessageText);
                case SendMessage sendMessage -> execute(sendMessage);
                case SendPhoto sendPhoto -> execute(sendPhoto);
                case SendDocument sendDocument -> execute(sendDocument);
                case SendAudio sendAudio -> execute(sendAudio);
                case SendVideo sendVideo -> execute(sendVideo);
                case SendPoll sendPoll -> execute(sendPoll);
                case DeliveryWithFeedback delivery -> executeWithFeedback(delivery);
                default -> log.info("Непонятный формат сообщения: {}", message);
            }
        } catch (TelegramApiException e) {
            log.info(ERROR_WITH_SENDING_MESSAGE, e);
        }
    }

    public void executeWithFeedback(DeliveryWithFeedback request) {
        try {
            execute(request.delivery());
        } catch (TelegramApiException e) {
            log.error("Не удалось доставить сообщение в чат {}", request.delivery().getChatId(), e);
            sendDeliveryFeedback(request.failureFeedback(), "ошибке отправки");
            publishDeliveryResult(request.deliveryCorrelationId(), false, e.getMessage());
            return;
        }

        sendDeliveryFeedback(request.successFeedback(), "успешной отправке");
        publishDeliveryResult(request.deliveryCorrelationId(), true, null);
    }

    private void sendDeliveryFeedback(SendMessage feedback, String result) {
        if (feedback == null) {
            return;
        }
        try {
            execute(feedback);
        } catch (TelegramApiException e) {
            log.error("Не удалось сообщить пользователю {} о {}", feedback.getChatId(), result, e);
        }
    }

    private void publishDeliveryResult(Long correlationId, boolean successful, String error) {
        if (correlationId == null) {
            return;
        }
        eventPublisher.publishEvent(new GamificationDeliveryResultEvent(
                correlationId,
                successful,
                error,
                LocalDateTime.now(ZoneId.of("Europe/Moscow"))));
    }

    private boolean badMessage(Update update) {
        // разрешенные действия будем пропускать
        boolean result = true;
        if (Objects.nonNull(update.getMessage()) && update.getMessage().isUserMessage()) {
            // любое сообщение в личке (текст, фото, документ)
            result = false;
        }
        if (Objects.nonNull(update.getCallbackQuery()) && update.getCallbackQuery().getMessage().isUserMessage()) {
            // любая кнопка в личке
            result = false;
        }
        if (update.hasPollAnswer()) {
            // если пришел опросник, то пропускаем
            result = false;
        }
        return result;
    }
}
