package com.nekitvp.marathonbot.service;

import com.nekitvp.marathonbot.bot.DeliveryWithFeedback;
import com.nekitvp.marathonbot.event.SendTelegramMessageEvent;
import com.nekitvp.marathonbot.model.MarathonEntity;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GamificationLetterSenderTest {

    @Test
    void createsCorrelatedMarkdownDeliveryWithoutManagerFeedback() throws Exception {
        LetterSender sender = new LetterSender(mock(ApplicationEventPublisher.class),
                new TemplatePhraseService(), mock(com.nekitvp.marathonbot.integration.yandex.service.YandexGptService.class));

        DeliveryWithFeedback delivery = sender.gamificationDelivery(42L, -100123L, "🏔️ *Вершина\\!*");

        assertThat(delivery.delivery().getChatId()).isEqualTo("-100123");
        assertThat(delivery.delivery().getParseMode()).isEqualTo("MarkdownV2");
        assertThat(delivery.delivery().getText()).isEqualTo("🏔️ *Вершина\\!*");
        assertThat(delivery.successFeedback()).isNull();
        assertThat(delivery.failureFeedback()).isNull();
        assertThat(delivery.deliveryCorrelationId()).isEqualTo(42L);
    }

    @Test
    void embedsExpeditionProgressIntoTheExistingStatisticsEvent() throws Exception {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        LetterSender sender = new LetterSender(publisher, new TemplatePhraseService(),
                mock(com.nekitvp.marathonbot.integration.yandex.service.YandexGptService.class));
        MarathonEntity marathon = MarathonEntity.builder()
                .groupId(-100123L)
                .dateStart(LocalDateTime.now().minusDays(2))
                .dateEnd(LocalDateTime.now().plusDays(10))
                .freeFailCount(5)
                .penaltyAmount(100)
                .build();

        sender.sendStatistics(marathon, Map.of(), "🥾 *Экспедиция*: ▰▰▰▱▱▱▱▱▱▱ 30%");

        ArgumentCaptor<SendTelegramMessageEvent> event = ArgumentCaptor.forClass(SendTelegramMessageEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue().getText())
                .contains("Привет, участники марафона", "Экспедиция", "30%")
                .containsOnlyOnce("Привет, участники марафона");
    }
}
