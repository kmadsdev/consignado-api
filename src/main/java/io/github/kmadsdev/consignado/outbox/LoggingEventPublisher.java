package io.github.kmadsdev.consignado.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default adapter: writes each event to the log. Useful locally and as a stand-in until a
 * broker is chosen. Payloads carry no personal data, so logging them is safe.
 */
@Component
@ConditionalOnProperty(name = "consignado.outbox.publisher", havingValue = "log", matchIfMissing = true)
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxMessage message) {
        log.info("event published type={} aggregate={} id={} payload={}",
                message.eventType(), message.aggregateId(), message.id(), message.payload());
    }
}
