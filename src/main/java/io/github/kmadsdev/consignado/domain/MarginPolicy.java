package io.github.kmadsdev.consignado.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Consignable margin: the share of the net salary that installments may take.
 *
 * <pre>
 * limit     = floor(netSalary * marginPercent / 100)
 * available = limit - committedInstallments
 * </pre>
 *
 * The cap is rounded DOWN, not HALF_EVEN: the margin is a legal ceiling, and rounding it
 * up by one cent would allow an installment above the limit.
 */
public final class MarginPolicy {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private MarginPolicy() {
    }

    /** The legal ceiling for the sum of installments, before any commitment is subtracted. */
    public static BigDecimal marginLimit(BigDecimal netSalary, BigDecimal marginPercent) {
        if (netSalary == null || netSalary.signum() <= 0) {
            throw new IllegalArgumentException("net salary must be positive");
        }
        if (marginPercent == null || marginPercent.signum() <= 0 || marginPercent.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("margin percent must be in (0, 100]");
        }
        return netSalary.multiply(marginPercent).divide(HUNDRED, Money.SCALE, RoundingMode.DOWN);
    }

    public static BigDecimal availableMargin(
            BigDecimal netSalary, BigDecimal marginPercent, BigDecimal committedInstallments) {
        BigDecimal committed = committedInstallments == null ? BigDecimal.ZERO : committedInstallments;
        BigDecimal available = marginLimit(netSalary, marginPercent).subtract(committed);
        return available.signum() < 0 ? BigDecimal.ZERO.setScale(Money.SCALE) : available.setScale(Money.SCALE);
    }

    public static void requireFits(BigDecimal installment, BigDecimal availableMargin) {
        if (installment.compareTo(availableMargin) > 0) {
            throw new MarginExceededException(availableMargin, installment);
        }
    }

    /**
     * Guards an income change: the limit that the new income gives must still cover what
     * is already committed. Without it the customer would be over the legal ceiling.
     */
    public static void requireCovers(BigDecimal marginLimit, BigDecimal committedInstallments) {
        if (marginLimit.compareTo(committedInstallments) < 0) {
            throw new IncomeBelowCommittedMarginException(
                    marginLimit, committedInstallments.setScale(Money.SCALE, RoundingMode.UNNECESSARY));
        }
    }
}
