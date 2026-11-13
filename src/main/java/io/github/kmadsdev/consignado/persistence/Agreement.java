package io.github.kmadsdev.consignado.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Employer agreement (convenio): margin cap, term cap and the rate offered. */
@Entity
@Table(name = "agreement")
public class Agreement {

    @Id
    private UUID id;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "max_margin_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxMarginPercent;

    @Column(name = "max_term_months", nullable = false)
    private int maxTermMonths;

    @Column(name = "monthly_interest_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal monthlyInterestRate;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Agreement() {
        // for JPA
    }

    public Agreement(String code, String name, BigDecimal maxMarginPercent, int maxTermMonths,
                     BigDecimal monthlyInterestRate, Instant now) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.name = name;
        this.maxMarginPercent = maxMarginPercent;
        this.maxTermMonths = maxTermMonths;
        this.monthlyInterestRate = monthlyInterestRate;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getMaxMarginPercent() {
        return maxMarginPercent;
    }

    public int getMaxTermMonths() {
        return maxTermMonths;
    }

    public BigDecimal getMonthlyInterestRate() {
        return monthlyInterestRate;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
