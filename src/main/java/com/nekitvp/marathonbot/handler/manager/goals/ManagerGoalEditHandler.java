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
public class ManagerGoalEditHandler extends AbstractManagerHandler {

    private final UserService userService;
    private final GoalService goalService;
    private final GoalRepository goalRepository;
    private final MarathonRepository marathonRepository;
    private final LetterSender letterSender;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_GOAL_EDIT;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long telegramUserId = getUserId(update);

        WizardContext ctx = readCtx(telegramUserId, userService, objectMapper);

        if (isNumericCallback(message)) {
            Long goalId = extractId(message);
            ctx.setSelectedGoalId(goalId);
            ctx.setPendingDeleteId(null);
            saveCtx(telegramUserId, ctx, userService, objectMapper);
            return showGoalDetail(message, goalId);
        }

        // State callback: check pending delete
        Long pendingDeleteId = ctx.getPendingDeleteId();
        if (pendingDeleteId != null) {
            GoalEntity deletedGoal = goalRepository.findById(pendingDeleteId).orElse(null);
            String deletedGoalName = deletedGoal != null ? deletedGoal.getName() : "цель";
            goalService.deleteGoal(pendingDeleteId);
            ctx.setSelectedGoalId(null);
            ctx.setPendingDeleteId(null);
            saveCtx(telegramUserId, ctx, userService, objectMapper);

            Long marathonId = ctx.getSelectedMarathonId();
            if (marathonId != null) {
                final String goalName = deletedGoalName;
                String managerName = userService.getUser(telegramUserId).getEffectiveName();
                Long selectedUserId = ctx.getSelectedUserId();
                UserEntity participant = selectedUserId != null ? userService.findByTelegramId(selectedUserId) : null;
                String participantName = participant != null ? participant.getEffectiveName() : "участника";
                marathonRepository.findById(marathonId).ifPresent(m -> {
                    if (m.getGroupId() != null) {
                        letterSender.publishTextEscape(m.getGroupId(),
                                "🗑 Менеджер " + managerName + " удалил цель «" + goalName + "» у " + participantName);
                    }
                });
            }

            return getDefaultMessage(message, "✅ Цель удалена.",
                    new InlineKeyboardBuilder().row(backToGoalsButton()).build());
        }

        Long selectedGoalId = ctx.getSelectedGoalId();
        if (selectedGoalId == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Цель не выбрана.")
                    .replyMarkup(new InlineKeyboardBuilder()
                            .row(backToGoalsButton())
                            .build())
                    .build();
        }

        // First delete click — show confirmation
        ctx.setPendingDeleteId(selectedGoalId);
        saveCtx(telegramUserId, ctx, userService, objectMapper);
        return showDeleteConfirmation(message, selectedGoalId);
    }

    private Object showGoalDetail(Message message, Long goalId) {
        GoalEntity goal = goalRepository.findById(goalId).orElse(null);
        if (goal == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Цель не найдена.")
                    .replyMarkup(new InlineKeyboardBuilder()
                            .row(backToGoalsButton())
                            .build())
                    .build();
        }

        boolean isReportGoal = goal.getPosition() == 0;
        String info = "🎯 Цель:\n" +
                "Название: " + goal.getName() + "\n" +
                "Позиция: " + (isReportGoal ? "Отчет (обязательная)" : goal.getPosition());

        InlineKeyboardBuilder builder = new InlineKeyboardBuilder();
        if (!isReportGoal) {
            builder.row(
                    InlineKeyboardButton.builder()
                            .text("✏️ Переименовать")
                            .callbackData(StateBot.MANAGER_GOAL_EDIT_NAME.name())
                            .build(),
                    InlineKeyboardButton.builder()
                            .text("🗑 Удалить")
                            .callbackData(StateBot.MANAGER_GOAL_EDIT.name())
                            .build()
            );
        }
        builder.row(backToGoalsButton());

        return getDefaultMessage(message, info, builder.build());
    }

    private Object showDeleteConfirmation(Message message, Long goalId) {
        GoalEntity goal = goalRepository.findById(goalId).orElse(null);
        String name = goal != null ? goal.getName() : "цель";

        String confirmText = "⚠️ Подтверди удаление цели \"" + name + "\".\n\nВсе записи истории по этой цели также будут удалены.";

        InlineKeyboardBuilder builder = new InlineKeyboardBuilder();
        builder.row(InlineKeyboardButton.builder()
                .text("✅ Да, удалить")
                .callbackData(StateBot.MANAGER_GOAL_EDIT.name())
                .build());
        builder.row(backToGoalsButton());

        return getDefaultMessage(message, confirmText, builder.build());
    }

    private InlineKeyboardButton backToGoalsButton() {
        return InlineKeyboardButton.builder()
                .text("◀ Назад к целям")
                .callbackData(StateBot.MANAGER_USER_GOALS.name())
                .build();
    }
}
