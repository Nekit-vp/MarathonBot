package com.nekitvp.marathonbot.handler.manager.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.manager.AbstractManagerHandler;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.repository.MarathonRepository;
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
public class ManagerToggleEveningHandler extends AbstractManagerHandler {

    private final UserService userService;
    private final MarathonRepository marathonRepository;
    private final LetterSender letterSender;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_TOGGLE_EVENING;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long telegramUserId = getUserId(update);

        WizardContext ctx = readCtx(telegramUserId, userService, objectMapper);
        Long marathonId = ctx.getSelectedMarathonId();
        if (marathonId == null) {
            return SendMessage.builder().chatId(message.getChatId()).text("Марафон не выбран.").build();
        }

        String managerName = userService.getUser(telegramUserId).getEffectiveName();
        boolean[] newVal = {false};
        marathonRepository.findById(marathonId).ifPresent(m -> {
            newVal[0] = !Boolean.TRUE.equals(m.getEveningQuestionsEnabled());
            m.setEveningQuestionsEnabled(newVal[0]);
            marathonRepository.save(m);
            if (m.getGroupId() != null) {
                String icon = newVal[0] ? "✅ включены" : "❌ выключены";
                letterSender.publishTextEscape(m.getGroupId(),
                        "⚙️ Менеджер " + managerName + " изменил настройку: вечерние вопросы " + icon);
            }
        });

        String icon = newVal[0] ? "✅ включены" : "❌ выключены";
        return SendMessage.builder()
                .chatId(message.getChatId())
                .text("✅ Вечерние вопросы: " + icon)
                .replyMarkup(new InlineKeyboardBuilder().row(backButton()).build())
                .build();
    }

    private InlineKeyboardButton backButton() {
        return InlineKeyboardButton.builder()
                .text("Вернуться к настройкам")
                .callbackData(StateBot.MANAGER_SETTINGS.name())
                .build();
    }
}
