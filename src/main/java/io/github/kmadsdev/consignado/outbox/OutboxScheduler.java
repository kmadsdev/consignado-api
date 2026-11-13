package io.github.kmadsdev.consignado.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the outbox. Tests turn it off and call {@link OutboxRelay} directly. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "consignado.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxScheduler {

    private final OutboxRelay relay;

    OutboxScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${consignado.outbox.poll-interval:PT2S}")
    void poll() {
        relay.relayBatch();
    }
}
