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

import java.util.ArrayList;
import java.util.List;

import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Component
@RequiredArgsConstructor
public class ManagerGoalImportHandler extends AbstractManagerHandler {

    private final UserService userService;
    private final GoalService goalService;
    private final MarathonRepository marathonRepository;
    private final LetterSender letterSender;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_GOAL_IMPORT;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long telegramUserId = getUserId(update);

        if (update.hasCallbackQuery()) {
            return getDefaultMessage(message, new InlineKeyboardBuilder().row(backButton()).build());
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

        String[] lines = message.getText().split("\\n");
        List<String> names = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }

        if (names.isEmpty()) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("❌ Не найдено ни одной цели. Введи цели, каждую с новой строки.")
                    .replyMarkup(new InlineKeyboardBuilder().row(backButton()).build())
                    .build();
        }

        List<String> tooLong = names.stream().filter(n -> n.length() > 100).toList();
        if (!tooLong.isEmpty()) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("❌ Некоторые цели слишком длинные (максимум 100 символов):\n" +
                            String.join("\n", tooLong))
                    .replyMarkup(new InlineKeyboardBuilder().row(backButton()).build())
                    .build();
        }

        int added = 0;
        int skipped = 0;
        for (String name : names) {
            try {
                goalService.addGoal(selectedUserId, marathonId, name);
                added++;
            } catch (IllegalStateException e) {
                skipped = names.size() - added;
                break;
            }
        }

        String result = "✅ Добавлено целей: " + added;
        if (skipped > 0) {
            result += "\n⚠️ Пропущено (достигнут максимум 10): " + skipped;
        }

        if (added > 0) {
            String userName = userService.findByTelegramId(selectedUserId) != null
                    ? userService.findByTelegramId(selectedUserId).getEffectiveName() : "участника";
            String managerName = userService.getUser(telegramUserId).getEffectiveName();
            final int addedCount = added;
            marathonRepository.findById(marathonId).ifPresent(m -> {
                if (m.getGroupId() != null) {
                    letterSender.publishTextEscape(m.getGroupId(),
                            "📋 Менеджер " + managerName + " добавил " + addedCount + " целей участнику " + userName);
                }
            });
        }

        return SendMessage.builder()
                .chatId(message.getChatId())
                .text(result)
                .replyMarkup(new InlineKeyboardBuilder().row(backButton()).build())
                .build();
    }

    private InlineKeyboardButton backButton() {
        return InlineKeyboardButton.builder()
                .text("◀ К целям участника")
                .callbackData(StateBot.MANAGER_USER_GOALS.name())
                .build();
    }
}
