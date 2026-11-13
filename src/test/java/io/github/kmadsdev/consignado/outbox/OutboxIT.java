package io.github.kmadsdev.consignado.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kmadsdev.consignado.testsupport.ApiTestBase;
import io.github.kmadsdev.consignado.testsupport.RecordingEventPublisher;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

class OutboxIT extends ApiTestBase {

    @Autowired
    private OutboxRelay relay;
    @Autowired
    private OutboxEventRepository outbox;
    @Autowired
    private RecordingEventPublisher publisher;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void statusChangesAreWrittenWithTheChangeAndRelayedInOrder() throws Exception {
        UUID contract = activeContract();

        assertThat(outbox.findByAggregateIdInOrder(contract)).extracting(OutboxEvent::getEventType)
                .containsExactly("contract.proposed", "contract.approved", "contract.active");

        drain();

        assertThat(publisher.publishedFor(contract)).extracting(OutboxMessage::eventType)
                .containsExactly("contract.proposed", "contract.approved", "contract.active");
        assertThat(outbox.findByAggregateIdInOrder(contract))
                .allSatisfy(event -> assertThat(event.getPublishedAt()).isNotNull());

        JsonNode payload = objectMapper.readTree(publisher.publishedFor(contract).getLast().payload());
        assertThat(payload.get("previousStatus").asText()).isEqualTo("APPROVED");
        assertThat(payload.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(payload.has("cpf")).isFalse();
    }

    @Test
    void aFailedEventBlocksLaterEventsOfTheSameAggregateUntilItGoesThrough() {
        drain();
        UUID contract = activeContract();
        publisher.failNextFor(contract, 1);

        drainOnce();

        OutboxEvent first = outbox.findByAggregateIdInOrder(contract).getFirst();
        assertThat(first.getPublishedAt()).isNull();
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getLastError()).contains("channel unavailable");
        assertThat(publisher.publishedFor(contract)).isEmpty();

        drain();

        assertThat(publisher.publishedFor(contract)).extracting(OutboxMessage::eventType)
                .containsExactly("contract.proposed", "contract.approved", "contract.active");
    }

    @Test
    void aRejectedChangeWritesNoEvent() {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "3000.00");
        UUID first = id(propose(id(simulate(customer, "6666.00", 12)), UUID.randomUUID().toString()));
        UUID second = id(propose(id(simulate(customer, "6666.00", 12)), UUID.randomUUID().toString()));
        action(first, "approve", null, null);

        assertThat(action(second, "approve", null, null).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(outbox.findByAggregateIdInOrder(second)).extracting(OutboxEvent::getEventType)
                .containsExactly("contract.proposed");
    }

    private UUID activeContract() {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "5000.00");
        UUID contract = id(propose(id(simulate(customer, "1000.00", 12)), UUID.randomUUID().toString()));
        action(contract, "approve", null, null);
        action(contract, "activate", null, Map.of());
        return contract;
    }

    /** Other test classes share the database, so relay until nothing is left. */
    private void drain() {
        for (int i = 0; i < 50 && !relay.relayBatch().isEmpty(); i++) {
            // keep relaying
        }
    }

    /** One pass over everything pending, without retrying failures in the same call. */
    private void drainOnce() {
        relay.relayBatch();
    }
}
