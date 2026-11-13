package io.github.kmadsdev.consignado.persistence;

import io.github.kmadsdev.consignado.domain.ScheduledInstallment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One persisted line of an active contract's schedule. Written once, never updated. */
@Entity
@Table(name = "installment")
public class Installment {

    @Id
    private UUID id;

    @Column(name = "contract_id", nullable = false, updatable = false)
    private UUID contractId;

    @Column(nullable = false, updatable = false)
    private int number;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal interest;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal amortization;

    @Column(name = "remaining_balance", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal remainingBalance;

    protected Installment() {
        // for JPA
    }

    public static Installment of(UUID contractId, ScheduledInstallment line) {
        Installment installment = new Installment();
        installment.id = UUID.randomUUID();
        installment.contractId = contractId;
        installment.number = line.number();
        installment.dueDate = line.dueDate();
        installment.amount = line.amount();
        installment.interest = line.interest();
        installment.amortization = line.amortization();
        installment.remainingBalance = line.remainingBalance();
        return installment;
    }

    public UUID getId() {
        return id;
    }

    public UUID getContractId() {
        return contractId;
    }

    public int getNumber() {
        return number;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getInterest() {
        return interest;
    }

    public BigDecimal getAmortization() {
        return amortization;
    }

    public BigDecimal getRemainingBalance() {
        return remainingBalance;
    }
}
