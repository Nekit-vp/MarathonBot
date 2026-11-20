package com.nekitvp.marathonbot.handler.manager.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.manager.AbstractManagerHandler;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.model.GoalEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.repository.GoalRepository;
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

@Component
@RequiredArgsConstructor
public class ManagerGoalEditNameHandler extends AbstractManagerHandler {

    private final UserService userService;
    private final GoalService goalService;
    private final GoalRepository goalRepository;
    private final MarathonRepository marathonRepository;
    private final LetterSender letterSender;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_GOAL_EDIT_NAME;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long telegramUserId = getUserId(update);

        if (update.hasCallbackQuery()) {
            WizardContext ctx = readCtx(telegramUserId, userService, objectMapper);
            Long goalId = ctx.getSelectedGoalId();
            GoalEntity current = goalId != null ? goalRepository.findById(goalId).orElse(null) : null;
            String currentName = current != null ? current.getName() : null;
            String prompt = "Введи новое название цели:"
                    + (currentName != null ? "\n\nТекущее название: «" + currentName + "»" : "");
            return getDefaultMessage(message, prompt, new InlineKeyboardBuilder()
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
        Long selectedGoalId = ctx.getSelectedGoalId();

        if (selectedGoalId == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Цель не выбрана.")
                    .replyMarkup(new InlineKeyboardBuilder().row(backToGoalsButton()).build())
                    .build();
        }

        GoalEntity goal = goalRepository.findById(selectedGoalId).orElse(null);
        String oldName = goal != null ? goal.getName() : "?";

        UserEntity manager = userService.findByTelegramId(telegramUserId);
        String managerName = manager != null ? manager.getEffectiveName() : "Менеджер";

        Long selectedUserId = ctx.getSelectedUserId();
        UserEntity participant = selectedUserId != null ? userService.findByTelegramId(selectedUserId) : null;
        String participantName = participant != null ? participant.getEffectiveName() : "участника";

        goalService.updateGoalName(selectedGoalId, name);

        Long marathonId = ctx.getSelectedMarathonId();
        if (marathonId != null) {
            marathonRepository.findById(marathonId).ifPresent(m -> {
                if (m.getGroupId() != null) {
                    letterSender.publishTextEscape(m.getGroupId(),
                            "✏️ Менеджер " + managerName + " переименовал цель у " + participantName + ":\n"
                            + "было: «" + oldName + "»\n"
                            + "стало: «" + name + "»");
                }
            });
        }

        return SendMessage.builder()
                .chatId(message.getChatId())
                .text("✅ Название цели обновлено: \"" + name + "\"")
                .replyMarkup(new InlineKeyboardBuilder().row(backToGoalsButton()).build())
                .build();
    }

    private InlineKeyboardButton backToGoalsButton() {
        return InlineKeyboardButton.builder()
                .text("◀ К целям участника")
                .callbackData(StateBot.MANAGER_USER_GOALS.name())
                .build();
    }
}
