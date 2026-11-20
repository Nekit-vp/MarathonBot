package com.nekitvp.marathonbot.handler;

import com.nekitvp.marathonbot.gamification.service.ReportGamificationService;
import com.nekitvp.marathonbot.gamification.service.ReportGamificationService.ReportRewards;
import com.nekitvp.marathonbot.gamification.service.ReportGamificationService.VisibleReward;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.service.HistoryService;
import com.nekitvp.marathonbot.service.LetterSender;
import com.nekitvp.marathonbot.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.util.Pair;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.polls.PollAnswer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GamificationReportIntegrationTest {

    @Mock private UserService userService;
    @Mock private HistoryService historyService;
    @Mock private LetterSender letterSender;
    @Mock private ReportGamificationService reportGamificationService;

    private UserEntity participant;
    private Update update;

    @BeforeEach
    void setUp() {
        MarathonEntity marathon = MarathonEntity.builder().id(35L).gamificationEnabled(true).build();
        participant = UserEntity.builder().telegramId(123L).marathon(marathon).build();
        when(userService.getUser(123L)).thenReturn(participant);

        User telegramUser = mock(User.class);
        when(telegramUser.getId()).thenReturn(123L);
        PollAnswer poll = mock(PollAnswer.class);
        when(poll.getUser()).thenReturn(telegramUser);
        when(poll.getOptionIds()).thenReturn(List.of(0));
        update = mock(Update.class);
        when(update.getPollAnswer()).thenReturn(poll);
    }

    @Test
    void todaysReportPersistsBeforeSynchronousRecalculationAndBundlesRewards() {
        List<Pair<String, Boolean>> report = List.of(Pair.of("Зарядка", true));
        when(historyService.createHistoryGoal(123L, List.of(0))).thenReturn(report);
        when(reportGamificationService.recalculateForReport(eq(35L), eq(123L), eq(LocalDate.now()), any()))
                .thenReturn(new ReportRewards(List.of(
                        new VisibleReward("✨", "Зелёный день"),
                        new VisibleReward("👟", "Первый шаг")), "💚 Зелёная серия: 3 · Отчёт №7"));

        SendMessage result = (SendMessage) new ReportSendHandler(
                userService, historyService, letterSender, reportGamificationService).handle(update);

        InOrder order = inOrder(historyService, reportGamificationService, letterSender);
        order.verify(historyService).createHistoryGoal(123L, List.of(0));
        order.verify(reportGamificationService).recalculateForReport(eq(35L), eq(123L), eq(LocalDate.now()), any());
        order.verify(letterSender).sendReport(participant, report, "💚 Зелёная серия: 3 · Отчёт №7");
        assertThat(result.getText()).contains("Новые награды", "✨ Зелёный день", "👟 Первый шаг");
    }

    @Test
    void yesterdaysReportUsesYesterdayAndBundlesOlderPendingRewards() {
        List<Pair<String, Boolean>> report = List.of(Pair.of("Зарядка", true));
        when(historyService.updateHistory(123L, List.of(0))).thenReturn(report);
        when(reportGamificationService.recalculateForReport(
                eq(35L), eq(123L), eq(LocalDate.now().minusDays(1)), any()))
                .thenReturn(new ReportRewards(List.of(new VisibleReward("🔥", "Железная неделя")), ""));

        SendMessage result = (SendMessage) new ReportSendYesterdayHandler(
                userService, historyService, letterSender, reportGamificationService).handle(update);

        assertThat(result.getText()).contains("Новые награды", "🔥 Железная неделя");
        verify(letterSender).sendYesterdayReport(participant, report, "");
    }

    @Test
    void gamificationFailureNeverFailsSavedReport() {
        List<Pair<String, Boolean>> report = List.of(Pair.of("Зарядка", true));
        when(historyService.createHistoryGoal(123L, List.of(0))).thenReturn(report);
        when(reportGamificationService.recalculateForReport(eq(35L), eq(123L), eq(LocalDate.now()), any()))
                .thenThrow(new IllegalStateException("expedition unavailable"));

        SendMessage result = (SendMessage) new ReportSendHandler(
                userService, historyService, letterSender, reportGamificationService).handle(update);

        assertThat(result.getText()).doesNotContain("Новые награды");
        verify(letterSender).sendReport(participant, report, "");
    }
}
