package com.nekitvp.marathonbot.bot;

import com.nekitvp.marathonbot.service.MarathonService;
import com.nekitvp.marathonbot.service.UpdateHandler;
import com.nekitvp.marathonbot.gamification.event.GamificationDeliveryResultEvent;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class TelegramBotTest {

    @Test
    void sendsSuccessFeedbackOnlyAfterDeliverySucceeds() throws TelegramApiException {
        TelegramBot bot = spy(new TelegramBot(mock(UpdateHandler.class), mock(MarathonService.class),
                mock(ApplicationEventPublisher.class)));
        DeliveryWithFeedback request = request();
        doReturn(mock(Message.class)).when(bot).execute(any(SendMessage.class));

        bot.executeWithFeedback(request);

        InOrder inOrder = inOrder(bot);
        inOrder.verify(bot).execute(request.delivery());
        inOrder.verify(bot).execute(request.successFeedback());
        verify(bot, never()).execute(request.failureFeedback());
    }

    @Test
    void sendsFailureFeedbackWhenDeliveryFails() throws TelegramApiException {
        TelegramBot bot = spy(new TelegramBot(mock(UpdateHandler.class), mock(MarathonService.class),
                mock(ApplicationEventPublisher.class)));
        DeliveryWithFeedback request = request();
        doAnswer(invocation -> {
            SendMessage message = invocation.getArgument(0);
            if (message == request.delivery()) {
                throw new TelegramApiException("Telegram rejected the delivery");
            }
            return mock(Message.class);
        }).when(bot).execute(any(SendMessage.class));

        bot.executeWithFeedback(request);

        verify(bot).execute(request.delivery());
        verify(bot).execute(request.failureFeedback());
        verify(bot, never()).execute(request.successFeedback());
    }

    @Test
    void publishesCorrelatedGamificationDeliveryResultWithoutManagerFeedback() throws TelegramApiException {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TelegramBot bot = spy(new TelegramBot(mock(UpdateHandler.class), mock(MarathonService.class), publisher));
        SendMessage message = SendMessage.builder().chatId(-100L).text("Текст").build();
        DeliveryWithFeedback request = new DeliveryWithFeedback(message, null, null, 42L);
        doReturn(mock(Message.class)).when(bot).execute(any(SendMessage.class));

        bot.executeWithFeedback(request);

        verify(bot).execute(message);
        ArgumentCaptor<GamificationDeliveryResultEvent> result =
                ArgumentCaptor.forClass(GamificationDeliveryResultEvent.class);
        verify(publisher).publishEvent(result.capture());
        assertThat(result.getValue().successful()).isTrue();
        assertThat(result.getValue().notificationId()).isEqualTo(42L);
        assertThat(result.getValue().error()).isNull();
        assertThat(result.getValue().occurredAt()).isNotNull();
    }

    @Test
    void publishesCorrelatedGamificationFailureResult() throws TelegramApiException {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TelegramBot bot = spy(new TelegramBot(mock(UpdateHandler.class), mock(MarathonService.class), publisher));
        SendMessage message = SendMessage.builder().chatId(-100L).text("Текст").build();
        DeliveryWithFeedback request = new DeliveryWithFeedback(message, null, null, 43L);
        doAnswer(invocation -> {
            throw new TelegramApiException("network");
        }).when(bot).execute(message);

        bot.executeWithFeedback(request);

        ArgumentCaptor<GamificationDeliveryResultEvent> result =
                ArgumentCaptor.forClass(GamificationDeliveryResultEvent.class);
        verify(publisher).publishEvent(result.capture());
        assertThat(result.getValue().successful()).isFalse();
        assertThat(result.getValue().notificationId()).isEqualTo(43L);
        assertThat(result.getValue().error()).contains("network");
        assertThat(result.getValue().occurredAt()).isNotNull();
    }

    private DeliveryWithFeedback request() {
        return new DeliveryWithFeedback(
                SendMessage.builder().chatId(-100L).text("Текст").build(),
                SendMessage.builder().chatId(1L).text("Успех").build(),
                SendMessage.builder().chatId(1L).text("Ошибка").build()
        );
    }
}
