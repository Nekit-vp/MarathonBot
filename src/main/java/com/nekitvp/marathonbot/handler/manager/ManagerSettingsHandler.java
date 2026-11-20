package com.nekitvp.marathonbot.handler.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;

@Component
@RequiredArgsConstructor
public class ManagerSettingsHandler extends AbstractManagerHandler {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final UserService userService;
    private final MarathonRepository marathonRepository;
    private final ObjectMapper objectMapper;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_SETTINGS;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long telegramUserId = getUserId(update);

        WizardContext ctx = readCtx(telegramUserId, userService, objectMapper);
        Long marathonId = ctx.getSelectedMarathonId();

        if (marathonId == null) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Марафон не выбран.")
                    .replyMarkup(new InlineKeyboardBuilder()
                            .row(createButtonByState(StateBot.MANAGER_MAIN))
                            .build())
                    .build();
        }

        Optional<MarathonEntity> opt = marathonRepository.findById(marathonId);
        if (opt.isEmpty()) {
            return SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("Марафон не найден.")
                    .replyMarkup(new InlineKeyboardBuilder()
                            .row(createButtonByState(StateBot.MANAGER_MAIN))
                            .build())
                    .build();
        }

        MarathonEntity marathon = opt.get();
        String text = buildSettingsText(marathon);
        InlineKeyboardMarkup keyboard = buildKeyboard(marathon);

        return getDefaultMessage(message, text, keyboard);
    }

    public String buildSettingsText(MarathonEntity marathon) {
        String start = marathon.getDateStart() != null
                ? marathon.getDateStart().format(DATE_FMT) : "не указан";
        String end = marathon.getDateEnd() != null
                ? marathon.getDateEnd().format(DATE_FMT) : "не указан";

        return "⚙️ Настройки марафона:\n\n" +
                "📋 Название: " + marathon.getName() + "\n" +
                "📝 Описание: " + (marathon.getDescription() != null ? marathon.getDescription() : "—") + "\n" +
                "📅 Старт: " + start + "\n" +
                "🏁 Финиш: " + end + "\n" +
                "🎫 Бесплатных штрафов: " + marathon.getFreeFailCount() + "\n" +
                "💸 Сумма штрафа: " + marathon.getPenaltyAmount() + " руб.\n" +
                "📊 Статус: " + marathon.getStatus().getDisplayName() + "\n" +
                "🗓 Выходной суббота: " + boolIcon(marathon.getRestOnSaturday()) + "\n" +
                "🗓 Выходной воскресенье: " + boolIcon(marathon.getRestOnSunday()) + "\n" +
                "🌆 Вечерние вопросы: " + boolIcon(marathon.getEveningQuestionsEnabled()) + "\n" +
                "🌴 Еженедельный выходной: " + boolIcon(marathon.getWeeklyDayOffEnabled()) + "\n" +
                "🏅 Награды и экспедиция: " + boolIcon(marathon.getGamificationEnabled());
    }

    private InlineKeyboardMarkup buildKeyboard(MarathonEntity marathon) {
        InlineKeyboardBuilder builder = new InlineKeyboardBuilder();
        builder.row(createButtonByState(StateBot.MANAGER_EDIT_NAME),
                    createButtonByState(StateBot.MANAGER_EDIT_DESCRIPTION));
        builder.row(createButtonByState(StateBot.MANAGER_EDIT_DATE_START),
                    createButtonByState(StateBot.MANAGER_EDIT_DATE_END));
        builder.row(createButtonByState(StateBot.MANAGER_EDIT_FREE_FAIL),
                    createButtonByState(StateBot.MANAGER_EDIT_PENALTY));
        builder.row(toggleButton("🗓 Вых. суббота: " + boolIcon(marathon.getRestOnSaturday()),
                StateBot.MANAGER_TOGGLE_REST_SAT));
        builder.row(toggleButton("🗓 Вых. воскресенье: " + boolIcon(marathon.getRestOnSunday()),
                StateBot.MANAGER_TOGGLE_REST_SUN));
        builder.row(toggleButton("🌆 Вечерние вопросы: " + boolIcon(marathon.getEveningQuestionsEnabled()),
                StateBot.MANAGER_TOGGLE_EVENING));
        builder.row(toggleButton("🌴 Еженед. выходной: " + boolIcon(marathon.getWeeklyDayOffEnabled()),
                StateBot.MANAGER_TOGGLE_WEEKLY_DAY_OFF));
        builder.row(toggleButton("🏅 Награды: " + boolIcon(marathon.getGamificationEnabled()),
                StateBot.MANAGER_GAMIFICATION));
        builder.row(createButtonByState(StateBot.MANAGER_ADD_MANAGER));
        builder.row(backButton());
        return builder.build();
    }

    private static String boolIcon(Boolean val) {
        return Boolean.TRUE.equals(val) ? "✅" : "❌";
    }

    private static InlineKeyboardButton toggleButton(String text, StateBot state) {
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(state.name())
                .build();
    }

    private static InlineKeyboardButton backButton() {
        return InlineKeyboardButton.builder()
                .text("Вернуться к марафону")
                .callbackData(StateBot.MANAGER_DETAIL.name())
                .build();
    }
}
