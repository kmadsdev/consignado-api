package io.github.kmadsdev.consignado.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Persistable;

class OutboxEventTest {

    /**
     * The id is assigned in the constructor. Unless the entity says it is new, Spring Data's
     * save() calls merge(), which runs a SELECT before every outbox insert.
     */
    @Test
    void aNewEventTellsSpringDataItIsNew() {
        OutboxEvent event = new OutboxEvent("LoanContract", UUID.randomUUID(), "contract.proposed", "{}", null,
                Instant.EPOCH);

        assertThat(event).isInstanceOfSatisfying(Persistable.class, persistable ->
                assertThat(persistable.isNew()).isTrue());
    }
}
