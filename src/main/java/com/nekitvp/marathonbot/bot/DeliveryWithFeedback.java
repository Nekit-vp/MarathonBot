package com.nekitvp.marathonbot.bot;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

/**
 * A message that must be delivered before the sender receives the final status.
 */
public record DeliveryWithFeedback(
        SendMessage delivery,
        SendMessage successFeedback,
        SendMessage failureFeedback,
        Long deliveryCorrelationId
) {
    public DeliveryWithFeedback(
            SendMessage delivery,
            SendMessage successFeedback,
            SendMessage failureFeedback
    ) {
        this(delivery, successFeedback, failureFeedback, null);
    }
}
