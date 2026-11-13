package io.github.kmadsdev.consignado.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** An accepted quote. Immutable after creation, so it has no version column. */
@Entity
@Table(name = "loan_simulation")
public class LoanSimulation {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "agreement_id", nullable = false)
    private UUID agreementId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal principal;

    @Column(name = "monthly_interest_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal monthlyInterestRate;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    @Column(name = "installment_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal installmentAmount;

    @Column(name = "total_payable", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalPayable;

    @Column(name = "available_margin", nullable = false, precision = 15, scale = 2)
    private BigDecimal availableMargin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected LoanSimulation() {
        // for JPA
    }

    @SuppressWarnings("java:S107") // a quote has this many facts; a builder would only hide it
    public LoanSimulation(UUID customerId, UUID agreementId, BigDecimal principal, BigDecimal monthlyInterestRate,
                          int termMonths, BigDecimal installmentAmount, BigDecimal totalPayable,
                          BigDecimal availableMargin, Instant createdAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.agreementId = agreementId;
        this.principal = principal;
        this.monthlyInterestRate = monthlyInterestRate;
        this.termMonths = termMonths;
        this.installmentAmount = installmentAmount;
        this.totalPayable = totalPayable;
        this.availableMargin = availableMargin;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getAgreementId() {
        return agreementId;
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

    public BigDecimal getTotalPayable() {
        return totalPayable;
    }

    public BigDecimal getAvailableMargin() {
        return availableMargin;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
