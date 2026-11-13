package io.github.kmadsdev.consignado.observability;

import io.github.kmadsdev.consignado.domain.ContractStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Domain counters next to the technical ones (HTTP, JVM, pool) that Actuator already
 * gives. Tags have a small fixed set of values: never put ids or amounts in a tag.
 */
@Component
public class BusinessMetrics {

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void contractTransition(ContractStatus from, ContractStatus to) {
        registry.counter("consignado.contract.transitions", "from", from.name(), "to", to.name()).increment();
    }

    /** outcome: accepted, margin_exceeded or term_exceeded. */
    public void simulation(String outcome) {
        registry.counter("consignado.simulations", "outcome", outcome).increment();
    }
}
