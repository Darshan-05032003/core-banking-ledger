package com.darshan.ledger.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

// Notifications recorded per channel, and events the consumer gave up on. The second number is
// the one to alert on: a dead letter is a customer who was not told about a movement on their
// account, and nobody will notice from the log alone.
@Component
public class NotificationMetrics {

    public static final String SENT = "notifications.sent";
    public static final String DEAD_LETTERED = "notifications.dead.lettered";
    public static final String CHANNEL_TAG = "channel";

    private final Map<NotificationChannel, Counter> sentByChannel = new EnumMap<>(NotificationChannel.class);
    private final Counter deadLettered;

    public NotificationMetrics(MeterRegistry registry) {
        for (NotificationChannel channel : NotificationChannel.values()) {
            sentByChannel.put(channel, Counter.builder(SENT)
                    .description("Notifications recorded for customers, by channel")
                    .tag(CHANNEL_TAG, channel.name())
                    .register(registry));
        }
        this.deadLettered = Counter.builder(DEAD_LETTERED)
                .description("Ledger events the notification consumer parked on the dead-letter topic")
                .register(registry);
    }

    public void sent(NotificationChannel channel) {
        sentByChannel.get(channel).increment();
    }

    public void deadLettered() {
        deadLettered.increment();
    }
}
