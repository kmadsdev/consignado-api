package io.github.kmadsdev.consignado.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Consignable margin: the share of the net salary that installments may take.
 *
 * <pre>
 * available = floor(netSalary * marginPercent / 100) - committedInstallments
 * </pre>
 *
 * The cap is rounded DOWN, not HALF_EVEN: the margin is a legal ceiling, and rounding it
 * up by one cent would allow an installment above the limit.
 */
public final class MarginPolicy {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private MarginPolicy() {
    }

    public static BigDecimal availableMargin(
            BigDecimal netSalary, BigDecimal marginPercent, BigDecimal committedInstallments) {
        if (netSalary == null || netSalary.signum() <= 0) {
            throw new IllegalArgumentException("net salary must be positive");
        }
        if (marginPercent == null || marginPercent.signum() <= 0 || marginPercent.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("margin percent must be in (0, 100]");
        }
        BigDecimal committed = committedInstallments == null ? BigDecimal.ZERO : committedInstallments;
        BigDecimal cap = netSalary.multiply(marginPercent).divide(HUNDRED, Money.SCALE, RoundingMode.DOWN);
        BigDecimal available = cap.subtract(committed);
        return available.signum() < 0 ? BigDecimal.ZERO.setScale(Money.SCALE) : available.setScale(Money.SCALE);
    }

    public static void requireFits(BigDecimal installment, BigDecimal availableMargin) {
        if (installment.compareTo(availableMargin) > 0) {
            throw new MarginExceededException(availableMargin, installment);
        }
    }
}
