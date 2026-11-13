package io.github.kmadsdev.consignado.domain;

import java.math.BigDecimal;

/** The monthly installment does not fit in the customer's consignable margin. */
public class MarginExceededException extends BusinessRuleViolationException {

    private final BigDecimal availableMargin;
    private final BigDecimal installment;

    public MarginExceededException(BigDecimal availableMargin, BigDecimal installment) {
        super("margin-exceeded",
                "Installment " + installment + " is above the available margin " + availableMargin);
        this.availableMargin = availableMargin;
        this.installment = installment;
    }

    public BigDecimal availableMargin() {
        return availableMargin;
    }

    public BigDecimal installment() {
        return installment;
    }
}
