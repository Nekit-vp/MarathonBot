package com.nekitvp.marathonbot.handler.manager;

import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.handler.AbstractHandler;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.service.UserService;
import com.nekitvp.marathonbot.util.InlineKeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import com.nekitvp.marathonbot.enumBot.MarathonStatus;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.nekitvp.marathonbot.util.TelegramUtil.createButtonByState;
import static com.nekitvp.marathonbot.util.TelegramUtil.button;

@Component
@RequiredArgsConstructor
public class ManagerMainHandler extends AbstractHandler {

    private final UserService userService;
    private final MarathonRepository marathonRepository;

    @Value("${superadmin.telegram-id}")
    private Long superadminId;

    @Override
    public StateBot getCurrentState() {
        return StateBot.MANAGER_MAIN;
    }

    @Override
    public StateBot getNextState() {
        return StateBot.MANAGER_DETAIL;
    }

    @Override
    public Object handle(Update update) {
        Message message = getMessage(update);
        Long userId = update.hasCallbackQuery()
                ? update.getCallbackQuery().getFrom().getId()
                : update.getMessage().getFrom().getId();

        // Сбрасываем wizard-контекст при любом входе в главное меню менеджера
        userService.updateInputContext(userId, null);

        Set<MarathonEntity> managed;
        if (userId.equals(superadminId)) {
            managed = new HashSet<>(marathonRepository.findAll());
        } else {
            managed = userService.getManagedMarathons(userId);
        }

        InlineKeyboardBuilder builder = new InlineKeyboardBuilder();

        if (managed == null || managed.isEmpty()) {
            builder.row(createButtonByState(StateBot.CREATE_MARATHON_INFO), createButtonByState(StateBot.START));
            return getDefaultMessage(message, "📋 У тебя пока нет марафонов.\n\nНажми «Создать марафон ➕», чтобы создать первый!", builder.build());
        }

        Map<MarathonStatus, Integer> statusOrder = Map.of(
                MarathonStatus.FINISHED, 0,
                MarathonStatus.ACTIVE, 1,
                MarathonStatus.WAITING_START, 2,
                MarathonStatus.DRAFT, 3
        );

        List<MarathonEntity> sorted = managed.stream()
                .sorted(Comparator
                        .comparingInt((MarathonEntity m) -> statusOrder.getOrDefault(m.getStatus(), 99))
                        .thenComparing(MarathonEntity::getDateStart,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        for (MarathonEntity m : sorted) {
            builder.row(button(m.getName() + " [" + m.getStatus().getDisplayName() + "]", m.getId()));
        }

        builder.row(createButtonByState(StateBot.CREATE_MARATHON_INFO), createButtonByState(StateBot.START));

        return getDefaultMessage(message, builder.build());
    }
}
