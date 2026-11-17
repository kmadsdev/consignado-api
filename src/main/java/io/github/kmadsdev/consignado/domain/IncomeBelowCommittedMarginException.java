package io.github.kmadsdev.consignado.domain;

import java.math.BigDecimal;

/**
 * A lower income would put the margin limit below the installments that APPROVED and
 * ACTIVE contracts already take from the paycheck.
 */
public class IncomeBelowCommittedMarginException extends BusinessRuleViolationException {

    private final BigDecimal marginLimit;
    private final BigDecimal committedInstallments;

    public IncomeBelowCommittedMarginException(BigDecimal marginLimit, BigDecimal committedInstallments) {
        super("income-below-committed-margin",
                "The new margin limit " + marginLimit + " is below the committed installments "
                        + committedInstallments);
        this.marginLimit = marginLimit;
        this.committedInstallments = committedInstallments;
    }

    public BigDecimal marginLimit() {
        return marginLimit;
    }

    public BigDecimal committedInstallments() {
        return committedInstallments;
    }
}
