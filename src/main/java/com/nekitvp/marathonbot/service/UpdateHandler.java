package com.nekitvp.marathonbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.FunctionBot;
import com.nekitvp.marathonbot.enumBot.MarathonStatus;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.Handler;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.service.MarathonService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Component
@Slf4j
@RequiredArgsConstructor
public class UpdateHandler {

    private static final String ADMIN_COMMAND = "/admin";
    private static final String JOIN_PREFIX = "/start join_";

    private final UserService userService;
    private final FunctionService functionService;
    private final MarathonService marathonService;
    private final MarathonRepository marathonRepository;
    private final ObjectMapper objectMapper;

    private final List<Handler> handlers;
    private final EnumMap<StateBot, Handler> stateToHandlerMap = new EnumMap<>(StateBot.class);

    @PostConstruct
    public void postConstruct() {
        handlers.forEach(handler -> stateToHandlerMap.put(handler.getCurrentState(), handler));
    }

    public Object handleMessage(Update update) {

        if (isMessageWithText(update)) { // пришел текст

            Message message = update.getMessage();
            Long telegramUserId = message.getFrom().getId();

            if (adminCommand(message)) {
                userService.updateUserState(telegramUserId, StateBot.ADMIN_MAIN);
            }

            // Ensure user exists
            StateBot state = userService.getUserState(telegramUserId, message);

            // Handle deep link invite: /start join_MARATHON_ID
            String text = message.getText();
            if (text != null && text.startsWith(JOIN_PREFIX)) {
                String marathonIdStr = text.substring(JOIN_PREFIX.length()).strip();
                try {
                    Long marathonId = Long.parseLong(marathonIdStr);
                    UserEntity user = userService.findByTelegramId(telegramUserId);
                    MarathonEntity current = user.getMarathon();

                    // Сценарий 1: уже в этом марафоне — тихо показываем экран «Мой Марафон»
                    if (current != null && current.getId().equals(marathonId)) {
                        userService.updateUserState(telegramUserId, StateBot.MARATHON);
                        return stateToHandlerMap.get(StateBot.MARATHON).handle(update);
                    }

                    // Сценарий 2: участвует в другом активном марафоне — просим подтверждение
                    if (current != null &&
                            (current.getStatus() == MarathonStatus.ACTIVE ||
                             current.getStatus() == MarathonStatus.WAITING_START)) {
                        String targetName = marathonRepository.findById(marathonId)
                                .map(MarathonEntity::getName).orElse("марафон");
                        WizardContext ctx = new WizardContext();
                        ctx.setPendingJoinMarathonId(marathonId);
                        try {
                            userService.updateInputContext(telegramUserId, objectMapper.writeValueAsString(ctx));
                        } catch (JsonProcessingException e) {
                            log.warn("Failed to serialize join context for user {}", telegramUserId, e);
                        }
                        userService.updateUserState(telegramUserId, StateBot.JOIN_MARATHON_CONFIRM);
                        return SendMessage.builder()
                                .chatId(message.getChatId())
                                .text("⚠️ Ты уже участвуешь в марафоне «" + current.getName() + "».\n\n" +
                                      "Точно хочешь вступить в марафон «" + targetName + "»?\n" +
                                      "Текущий марафон будет заменён.")
                                .replyMarkup(new InlineKeyboardBuilder()
                                        .row(createButtonByState(StateBot.JOIN_MARATHON_CONFIRM))
                                        .row(createButtonByState(StateBot.MARATHON))
                                        .build())
                                .build();
                    }

                    // Сценарий 3: обычное вступление
                    String resultMsg = marathonService.handleJoin(user, marathonId);
                    userService.updateUserState(telegramUserId, StateBot.MARATHON);
                    return SendMessage.builder()
                            .chatId(message.getChatId())
                            .text(resultMsg)
                            .replyMarkup(InlineKeyboardMarkup.builder()
                                    .keyboard(List.of(List.of(createButtonByState(StateBot.MARATHON))))
                                    .build())
                            .build();
                } catch (NumberFormatException e) {
                    log.warn("Invalid marathon ID in deep link: {}", marathonIdStr);
                }
            }

            logSendText(message, state);
            return stateToHandlerMap.get(state).handle(update);

        } else if (update.hasCallbackQuery()) { //нажата кнопка

            Long telegramUserId = update.getCallbackQuery().getFrom().getId();
            StateBot state = StateBot.getStateBotByCallBackQuery(update.getCallbackQuery().getData());

            if (Objects.nonNull(state)) { // значит нажата кнопка с состоянием
                logPressedButton(update, state);
                StateBot actualState = userService.updateUserState(telegramUserId, state);
                return stateToHandlerMap.get(actualState).handle(update);

            } else { // значит выбран как либо вариант или функция
                FunctionBot function = FunctionBot.getFunctionBotByCallBackQuery(update.getCallbackQuery().getData());

                if (Objects.nonNull(function)) { // нажата функция
                    function.getFunction().accept(functionService);
                    userService.updateUserState(telegramUserId, function.getStateBot());
                    return stateToHandlerMap.get(function.getStateBot()).handle(update);
                } else { // выбран id
                    StateBot stateBot = userService.findByTelegramId(telegramUserId).getState();
                    StateBot newState = stateToHandlerMap.get(stateBot).getNextState();
                    if (Objects.nonNull(newState)) {
                        userService.updateUserState(telegramUserId, newState);
                        Message message = (Message) update.getCallbackQuery().getMessage();
                        message.setText(update.getCallbackQuery().getData());
                        return stateToHandlerMap.get(newState).handle(update);
                    }
                }
            }

        } else if (isFile(update)) { // отправлен файл
            log.info("Пришел файл от " + update.getMessage().getFrom().getFirstName());
            Long telegramUserId = update.getMessage().getFrom().getId();
            StateBot stateBot = userService.findByTelegramId(telegramUserId).getState();
            StateBot newState = stateToHandlerMap.get(stateBot).getNextState();
            if (Objects.nonNull(newState)) {
                userService.updateUserState(telegramUserId, newState);
                return stateToHandlerMap.get(newState).handle(update);
            }
        } else if (update.hasPollAnswer()) {
            log.info("Пришел опрос от " + update.getPollAnswer().getUser().getFirstName());
            Long telegramUserId = update.getPollAnswer().getUser().getId();
            StateBot stateBot = userService.findByTelegramId(telegramUserId).getState();
            StateBot newState = stateToHandlerMap.get(stateBot).getNextState();
            if (Objects.nonNull(newState)) {
                userService.updateUserState(telegramUserId, newState);
                return stateToHandlerMap.get(newState).handle(update);
            }


        }
        return null;
    }

    private static void logSendText(Message message, StateBot state) {
        log.info("User @{}:{} sent '{}'. State is {}", message.getFrom().getUserName(), message.getFrom().getFirstName(), message.getText(), state.name());
    }

    private static void logPressedButton(Update update, StateBot state) {
        log.info("User @{}:{} pressed button '{}'", update.getCallbackQuery().getFrom().getUserName(),
                update.getCallbackQuery().getFrom().getFirstName(),
                state.getButtonInThisState());
    }

    private boolean adminCommand(Message message) {
        return message.hasEntities() && ADMIN_COMMAND.equals(message.getText())
                && userService.checkIsAdmin(message.getFrom().getId());
    }

    private boolean isMessageWithText(Update update) {
        return !update.hasCallbackQuery() && update.hasMessage() && update.getMessage().hasText();
    }

    private boolean isFile(Update update) {
        return update.hasMessage() &&
                (update.getMessage().hasPhoto() ||
                        update.getMessage().hasDocument() ||
                        update.getMessage().hasVideo() ||
                        update.getMessage().hasAudio());
    }
}
