package com.nekitvp.marathonbot.handler.manager.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.manager.AbstractManagerHandler;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.service.GoalService;
import com.nekitvp.marathonbot.service.LetterSender;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Component
@RequiredArgsConstructor
public class ManagerGoalAddHandler extends AbstractManagerHandler {

    private final UserService userService;
    private final GoalService goalService;
    private final MarathonRepository marathonRepository;
    private final LetterSender letterSender;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_GOAL_ADD;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long telegramUserId = getUserId(update);

        if (update.hasCallbackQuery()) {
            return getDefaultMessage(message, new InlineKeyboardBuilder()
                    .row(backToGoalsButton())
                    .build());
        }

        String name = message.getText().trim();
        if (name.isEmpty()) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("❌ Название не может быть пустым.")
                    .replyMarkup(new InlineKeyboardBuilder().row(backToGoalsButton()).build())
                    .build();
        }
        if (name.length() > 100) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("❌ Название слишком длинное (максимум 100 символов).")
                    .replyMarkup(new InlineKeyboardBuilder().row(backToGoalsButton()).build())
                    .build();
        }

        WizardContext ctx = readCtx(telegramUserId, userService, objectMapper);
        Long selectedUserId = ctx.getSelectedUserId();
        Long marathonId = ctx.getSelectedMarathonId();

        if (selectedUserId == null || marathonId == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Участник или марафон не выбран.")
                    .replyMarkup(new InlineKeyboardBuilder()
                            .row(createButtonByState(StateBot.MANAGER_GOALS_SELECT_USER))
                            .build())
                    .build();
        }

        try {
            goalService.addGoal(selectedUserId, marathonId, name);
            String userName = userService.findByTelegramId(selectedUserId) != null
                    ? userService.findByTelegramId(selectedUserId).getEffectiveName() : "участника";
            String managerName = userService.getUser(telegramUserId).getEffectiveName();
            marathonRepository.findById(marathonId).ifPresent(m -> {
                if (m.getGroupId() != null) {
                    letterSender.publishTextEscape(m.getGroupId(),
                            "🎯 Менеджер " + managerName + " добавил цель «" + name + "» участнику " + userName);
                }
            });
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("✅ Цель \"" + name + "\" добавлена!")
                    .replyMarkup(new InlineKeyboardBuilder().row(backToGoalsButton()).build())
                    .build();
        } catch (IllegalStateException e) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("❌ " + e.getMessage())
                    .replyMarkup(new InlineKeyboardBuilder().row(backToGoalsButton()).build())
                    .build();
        }
    }

    private InlineKeyboardButton backToGoalsButton() {
        return InlineKeyboardButton.builder()
                .text("◀ К целям участника")
                .callbackData(StateBot.MANAGER_USER_GOALS.name())
                .build();
    }
}
