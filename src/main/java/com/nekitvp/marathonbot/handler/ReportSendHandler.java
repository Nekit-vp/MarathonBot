package com.nekitvp.marathonbot.handler;

import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.gamification.service.ReportGamificationService;
import com.nekitvp.marathonbot.service.HistoryService;
import com.nekitvp.marathonbot.service.LetterSender;
import com.nekitvp.marathonbot.service.UserService;
import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReportSendHandler extends AbstractHandler {

    private final UserService userService;
    private final HistoryService historyService;
    private  final LetterSender letterSender;
    private final ReportGamificationService reportGamificationService;


    @Override
    public StateBot getCurrentState() {
        return StateBot.REPORT_SEND;
    }

    @Override
    public Object handle(Update update) {

        var poll = update.getPollAnswer();
        var telegramId = poll.getUser().getId();

        List<Pair<String, Boolean>> report = historyService.createHistoryGoal(telegramId, poll.getOptionIds());

        var user = userService.getUser(telegramId);

        String confirmation = getCurrentState().getMessage();
        var rewards = ReportGamificationService.ReportRewards.empty();
        try {
            rewards = reportGamificationService.recalculateForReport(
                    user.getMarathon().getId(), telegramId, LocalDate.now(), LocalDateTime.now());
            confirmation = rewards.appendTo(confirmation);
        } catch (RuntimeException exception) {
            log.error("Saved report without interactive rewards: marathon={}, user={}",
                    user.getMarathon().getId(), telegramId, exception);
        }
        letterSender.sendReport(user, report, rewards.groupTail());

        userService.updateUserState(telegramId, StateBot.START);
        return SendMessage.builder()
                .chatId(telegramId)
                .text(confirmation)
                .replyMarkup(getKeyboardDefault(StateBot.START))
                .build();

    }
}
