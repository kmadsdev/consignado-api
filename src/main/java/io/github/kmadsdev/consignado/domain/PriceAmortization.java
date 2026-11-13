package io.github.kmadsdev.consignado.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Price (French) amortization: fixed installments, interest on the outstanding balance.
 *
 * <pre>
 * PMT = P * i * (1 + i)^n / ((1 + i)^n - 1)
 * </pre>
 *
 * Intermediate math uses {@link MathContext#DECIMAL128} (34 significant digits). Each
 * stored value is rounded to cents with HALF_EVEN. Because the installment is rounded,
 * the sum of amortizations would not match the principal exactly, so the last
 * installment absorbs the remainder: it amortizes the whole remaining balance and the
 * schedule always ends at exactly zero.
 *
 * <p>IOF and CET are not computed here. See docs/adr/0002-money-and-rounding.md.
 */
public final class PriceAmortization {

    public static final int MAX_TERM_MONTHS = 420;
    private static final MathContext MC = MathContext.DECIMAL128;

    private PriceAmortization() {
    }

    /** The fixed monthly installment, rounded to cents. */
    public static BigDecimal installment(BigDecimal principal, BigDecimal monthlyRate, int termMonths) {
        BigDecimal p = Money.requirePositiveAmount(principal, "principal");
        validate(monthlyRate, termMonths);
        if (monthlyRate.signum() == 0) {
            return p.divide(BigDecimal.valueOf(termMonths), Money.SCALE, Money.ROUNDING);
        }
        BigDecimal factor = BigDecimal.ONE.add(monthlyRate).pow(termMonths, MC);
        BigDecimal pmt = p.multiply(monthlyRate, MC)
                .multiply(factor, MC)
                .divide(factor.subtract(BigDecimal.ONE), MC);
        return Money.round(pmt);
    }

    /** Full schedule. The first installment is due on {@code firstDueDate}, then monthly. */
    public static List<ScheduledInstallment> schedule(
            BigDecimal principal, BigDecimal monthlyRate, int termMonths, LocalDate firstDueDate) {
        BigDecimal pmt = installment(principal, monthlyRate, termMonths);
        BigDecimal balance = Money.round(principal);
        List<ScheduledInstallment> lines = new ArrayList<>(termMonths);

        for (int n = 1; n <= termMonths; n++) {
            BigDecimal interest = Money.round(balance.multiply(monthlyRate, MC));
            boolean last = n == termMonths;
            BigDecimal amortization = last ? balance : pmt.subtract(interest);
            BigDecimal amount = last ? interest.add(balance) : pmt;
            balance = balance.subtract(amortization);
            lines.add(new ScheduledInstallment(
                    n, firstDueDate.plusMonths(n - 1L), amount, interest, amortization, balance));
        }
        return List.copyOf(lines);
    }

    public static BigDecimal totalPayable(List<ScheduledInstallment> schedule) {
        return schedule.stream()
                .map(ScheduledInstallment::amount)
                .reduce(BigDecimal.ZERO.setScale(Money.SCALE), BigDecimal::add);
    }

    private static void validate(BigDecimal monthlyRate, int termMonths) {
        if (monthlyRate == null || monthlyRate.signum() < 0 || monthlyRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("monthly rate must be in [0, 1)");
        }
        if (termMonths < 1 || termMonths > MAX_TERM_MONTHS) {
            throw new IllegalArgumentException("term must be between 1 and " + MAX_TERM_MONTHS + " months");
        }
    }
}
