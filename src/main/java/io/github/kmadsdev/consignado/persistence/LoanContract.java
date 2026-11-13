package io.github.kmadsdev.consignado.persistence;

import io.github.kmadsdev.consignado.domain.ContractStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A loan contract. The loan terms are copied from the simulation at proposal time, so a
 * contract does not change if the agreement's rate changes later. Status changes go
 * through {@link #moveTo}, which asks the domain state machine first.
 */
@Entity
@Table(name = "loan_contract")
public class LoanContract {

    @Id
    private UUID id;

    @Column(name = "simulation_id", nullable = false, updatable = false)
    private UUID simulationId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContractStatus status;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal principal;

    @Column(name = "monthly_interest_rate", nullable = false, updatable = false, precision = 9, scale = 6)
    private BigDecimal monthlyInterestRate;

    @Column(name = "term_months", nullable = false, updatable = false)
    private int termMonths;

    @Column(name = "installment_amount", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal installmentAmount;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "status_reason")
    private String statusReason;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LoanContract() {
        // for JPA
    }

    /** Turns a simulation into a proposal: SIMULATED -> PROPOSED. */
    public static LoanContract propose(LoanSimulation simulation, String idempotencyKey, Instant now) {
        LoanContract contract = new LoanContract();
        contract.id = UUID.randomUUID();
        contract.simulationId = simulation.getId();
        contract.customerId = simulation.getCustomerId();
        contract.status = ContractStatus.SIMULATED.transitionTo(ContractStatus.PROPOSED);
        contract.principal = simulation.getPrincipal();
        contract.monthlyInterestRate = simulation.getMonthlyInterestRate();
        contract.termMonths = simulation.getTermMonths();
        contract.installmentAmount = simulation.getInstallmentAmount();
        contract.idempotencyKey = idempotencyKey;
        contract.createdAt = now;
        contract.updatedAt = now;
        return contract;
    }

    /** Applies a transition and returns the previous status. Throws if the move is not allowed. */
    public ContractStatus moveTo(ContractStatus target, String reason, Instant now) {
        ContractStatus previous = status;
        status = status.transitionTo(target);
        statusReason = reason;
        updatedAt = now;
        return previous;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSimulationId() {
        return simulationId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public ContractStatus getStatus() {
        return status;
    }

    public BigDecimal getPrincipal() {
        return principal;
    }

    public BigDecimal getMonthlyInterestRate() {
        return monthlyInterestRate;
    }

    public int getTermMonths() {
        return termMonths;
    }

    public BigDecimal getInstallmentAmount() {
        return installmentAmount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
