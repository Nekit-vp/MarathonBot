package com.nekitvp.marathonbot.handler.manager.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.MarathonStatus;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.manager.AbstractManagerHandler;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.Objects;

@Component
public class ManagerGamificationHandler extends AbstractManagerHandler {

    private final UserService userService;
    private final MarathonRepository marathonRepository;
    private final ObjectMapper objectMapper;
    private final Long superadminId;
    private final boolean productionEnableAvailable;

    public ManagerGamificationHandler(
            UserService userService,
            MarathonRepository marathonRepository,
            ObjectMapper objectMapper,
            @Value("${superadmin.telegram-id:0}") Long superadminId,
            @Value("${gamification.manager-enable-available:false}") boolean productionEnableAvailable
    ) {
        this.userService = Objects.requireNonNull(userService, "userService");
        this.marathonRepository = Objects.requireNonNull(marathonRepository, "marathonRepository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.superadminId = superadminId;
        this.productionEnableAvailable = productionEnableAvailable;
    }

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_GAMIFICATION;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long managerId = getUserId(update);
        WizardContext context = readCtx(managerId, userService, objectMapper);
        Long marathonId = context.getSelectedMarathonId();
        if (marathonId == null) {
            return message(message, "Марафон не выбран.");
        }
        MarathonEntity marathon = marathonRepository.findById(marathonId).orElse(null);
        if (marathon == null) {
            return message(message, "Марафон не найден.");
        }
        if (!hasAccess(managerId, marathonId, userService, superadminId)) {
            return message(message, "⛔ Нет доступа к настройкам этого марафона.");
        }

        boolean enabled = Boolean.TRUE.equals(marathon.getGamificationEnabled());
        context.setPendingGamificationEnabled(!enabled);
        saveCtx(managerId, context, userService, objectMapper);
        StringBuilder text = new StringBuilder()
                .append("🏅 Награды и командная экспедиция\n\n")
                .append("Марафон: ").append(marathon.getName()).append("\n")
                .append("Статус игры: ").append(enabled ? "✅ включены" : "❌ выключены").append("\n\n")
                .append("В каталоге запуска:\n")
                .append("🏅 повторяемые медали за сильные дни и время отчёта;\n")
                .append("🏆 достижения и серии отчётов/целей;\n")
                .append("🏔 командная экспедиция с точками 25 / 50 / 75 / 100%.\n\n");
        appendAvailability(text, marathon, enabled);

        InlineKeyboardBuilder keyboard = new InlineKeyboardBuilder();
        if (enabled || canEnable(marathon)) {
            keyboard.row(InlineKeyboardButton.builder()
                    .text(enabled ? "⚠️ Подтвердить выключение" : "✅ Подтвердить включение")
                    .callbackData(StateBot.MANAGER_TOGGLE_GAMIFICATION.name())
                    .build());
        } else {
            keyboard.row(InlineKeyboardButton.builder()
                    .text("🔄 Обновить статус")
                    .callbackData(StateBot.MANAGER_GAMIFICATION.name())
                    .build());
        }
        keyboard.row(backButton());
        return getDefaultMessage(message, text.toString(), keyboard.build());
    }

    private void appendAvailability(StringBuilder text, MarathonEntity marathon, boolean enabled) {
        if (enabled) {
            text.append("Выключение остановит новые награды и сообщения, но всё заработанное сохранится. ")
                    .append("Подтверди действие кнопкой ниже.");
            return;
        }
        if (marathon.getStatus() == MarathonStatus.DRAFT) {
            text.append("ℹ️ Это черновик. Сначала привяжи чат и подготовь марафон к старту.");
        } else if (marathon.getStatus() == MarathonStatus.FINISHED) {
            text.append("ℹ️ Марафон уже завершён — новую экспедицию включить нельзя.");
        } else if (marathon.getGroupId() == null) {
            text.append("⚠️ Сначала привяжи групповой чат: без него презентация запуска не будет доставлена.");
        } else if (!productionEnableAvailable) {
            text.append("⏳ Включение ещё недоступно: готовим проверенные сообщения и расписание. ")
                    .append("Перед запуском бот обязательно попросит подтвердить действие.");
        } else {
            text.append("При включении прошлые личные награды появятся тихо, без спама, ")
                    .append("а экспедиция начнётся с текущего состава. Подтверди действие кнопкой ниже.");
        }
    }

    private boolean canEnable(MarathonEntity marathon) {
        return productionEnableAvailable
                && marathon.getStatus() != MarathonStatus.DRAFT
                && marathon.getStatus() != MarathonStatus.FINISHED;
    }

    static boolean hasAccess(Long managerId, Long marathonId, UserService userService, Long superadminId) {
        if (Objects.equals(managerId, superadminId)) {
            return true;
        }
        return userService.getManagedMarathons(managerId).stream()
                .anyMatch(managed -> Objects.equals(managed.getId(), marathonId));
    }

    private Object message(Message source, String text) {
        return getDefaultMessage(source, text, new InlineKeyboardBuilder().row(backButton()).build());
    }

    private static InlineKeyboardButton backButton() {
        return InlineKeyboardButton.builder()
                .text("Вернуться к настройкам")
                .callbackData(StateBot.MANAGER_SETTINGS.name())
                .build();
    }
}
