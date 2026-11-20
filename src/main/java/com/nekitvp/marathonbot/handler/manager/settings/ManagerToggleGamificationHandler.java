package com.nekitvp.marathonbot.handler.manager.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.gamification.service.GamificationActivationService;
import com.nekitvp.marathonbot.handler.manager.AbstractManagerHandler;
import com.nekitvp.marathonbot.handler.manager.create.WizardContext;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.Objects;

@Component
public class ManagerToggleGamificationHandler extends AbstractManagerHandler {

    private final UserService userService;
    private final MarathonRepository marathonRepository;
    private final GamificationActivationService activationService;
    private final ObjectMapper objectMapper;
    private final Long superadminId;
    private final boolean productionEnableAvailable;

    public ManagerToggleGamificationHandler(
            UserService userService,
            MarathonRepository marathonRepository,
            GamificationActivationService activationService,
            ObjectMapper objectMapper,
            @Value("${superadmin.telegram-id:0}") Long superadminId,
            @Value("${gamification.manager-enable-available:false}") boolean productionEnableAvailable
    ) {
        this.userService = Objects.requireNonNull(userService, "userService");
        this.marathonRepository = Objects.requireNonNull(marathonRepository, "marathonRepository");
        this.activationService = Objects.requireNonNull(activationService, "activationService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.superadminId = superadminId;
        this.productionEnableAvailable = productionEnableAvailable;
    }

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_TOGGLE_GAMIFICATION;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long managerId = getUserId(update);
        WizardContext context = readCtx(managerId, userService, objectMapper);
        Long marathonId = context.getSelectedMarathonId();
        if (marathonId == null) {
            return response(message, "Марафон не выбран.");
        }
        MarathonEntity marathon = marathonRepository.findById(marathonId).orElse(null);
        if (marathon == null) {
            return response(message, "Марафон не найден.");
        }
        if (!ManagerGamificationHandler.hasAccess(managerId, marathonId, userService, superadminId)) {
            return response(message, "⛔ Нет доступа к настройкам этого марафона.");
        }

        Boolean requestedEnabled = context.getPendingGamificationEnabled();
        if (requestedEnabled == null) {
            return response(message, "ℹ️ Действие не ожидает подтверждения. Открой настройку ещё раз.");
        }
        if (!requestedEnabled) {
            activationService.disable(marathonId);
            clearIntent(managerId, context);
            return response(message,
                    "❌ Награды и экспедиция выключены. Все заработанные медали, достижения и маршрут сохранены.");
        }
        if (!productionEnableAvailable) {
            return response(message,
                    "⏳ Включение ещё недоступно: сначала должны пройти проверки сообщений и расписания.");
        }
        try {
            GamificationActivationService.ActivationResult result = activationService.enable(marathonId, managerId);
            clearIntent(managerId, context);
            String launch = result.launchRequired() && marathon.getGroupId() == null
                    ? " Чат не привязан: награды работают, но презентация пока не доставлена."
                    : result.launchRequired()
                    ? " Презентация запуска будет отправлена один раз."
                    : " Повторная презентация не требуется.";
            return response(message,
                    "✅ Награды и экспедиция включены! Прошлые личные награды рассчитаны тихо." + launch);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return response(message, "⚠️ Не удалось включить игру: " + exception.getMessage());
        }
    }

    private void clearIntent(Long managerId, WizardContext context) {
        context.setPendingGamificationEnabled(null);
        saveCtx(managerId, context, userService, objectMapper);
    }

    private Object response(Message message, String text) {
        return getDefaultMessage(message, text, new InlineKeyboardBuilder()
                .row(InlineKeyboardButton.builder()
                        .text("К наградам и экспедиции")
                        .callbackData(StateBot.MANAGER_GAMIFICATION.name())
                        .build())
                .build());
    }
}
