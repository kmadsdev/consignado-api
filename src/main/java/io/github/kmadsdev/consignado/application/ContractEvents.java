package io.github.kmadsdev.consignado.application;

import io.github.kmadsdev.consignado.domain.ContractStatus;
import io.github.kmadsdev.consignado.outbox.OutboxWriter;
import io.github.kmadsdev.consignado.persistence.LoanContract;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Turns contract status changes into outbox events: contract.proposed, contract.approved, ... */
@Component
class ContractEvents {

    static final String AGGREGATE_TYPE = "LoanContract";

    private final OutboxWriter outbox;

    ContractEvents(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** Event payload. No personal data: consumers fetch details by id if they need them. */
    record ContractStatusChanged(UUID contractId, UUID customerId, ContractStatus previousStatus,
                                 ContractStatus status, BigDecimal principal, BigDecimal installmentAmount,
                                 int termMonths, long contractVersion, Instant occurredAt) {
    }

    void statusChanged(LoanContract contract, ContractStatus previous) {
        outbox.append(AGGREGATE_TYPE, contract.getId(), eventType(contract.getStatus()),
                new ContractStatusChanged(contract.getId(), contract.getCustomerId(), previous, contract.getStatus(),
                        contract.getPrincipal(), contract.getInstallmentAmount(), contract.getTermMonths(),
                        contract.getVersion(), contract.getUpdatedAt()));
    }

    static String eventType(ContractStatus status) {
        return "contract." + status.name().toLowerCase(Locale.ROOT);
    }
}
