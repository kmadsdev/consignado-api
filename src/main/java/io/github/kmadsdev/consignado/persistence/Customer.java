package io.github.kmadsdev.consignado.persistence;

import io.github.kmadsdev.consignado.domain.Cpf;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A borrower. References its agreement by id instead of a JPA association: the two have
 * separate lifecycles, and loading one never needs the other's object graph.
 */
@Entity
@Table(name = "customer")
public class Customer {

    @Id
    private UUID id;

    @Column(nullable = false, length = 11)
    private Cpf cpf;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(name = "agreement_id", nullable = false)
    private UUID agreementId;

    @Column(name = "net_monthly_salary", nullable = false, precision = 15, scale = 2)
    private BigDecimal netMonthlySalary;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Customer() {
        // for JPA
    }

    public Customer(Cpf cpf, String fullName, UUID agreementId, BigDecimal netMonthlySalary, Instant now) {
        this.id = UUID.randomUUID();
        this.cpf = cpf;
        this.fullName = fullName;
        this.agreementId = agreementId;
        this.netMonthlySalary = netMonthlySalary;
        this.createdAt = now;
    }

    /** The only field that changes after registration. The margin rule is checked by the use case. */
    public void changeNetMonthlySalary(BigDecimal netMonthlySalary) {
        this.netMonthlySalary = netMonthlySalary;
    }

    public UUID getId() {
        return id;
    }

    public Cpf getCpf() {
        return cpf;
    }

    public String getFullName() {
        return fullName;
    }

    public UUID getAgreementId() {
        return agreementId;
    }

    public BigDecimal getNetMonthlySalary() {
        return netMonthlySalary;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
