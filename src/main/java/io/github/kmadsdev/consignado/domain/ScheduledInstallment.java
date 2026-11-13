package io.github.kmadsdev.consignado.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One line of an amortization schedule. {@code amount = interest + amortization}. */
public record ScheduledInstallment(
        int number,
        LocalDate dueDate,
        BigDecimal amount,
        BigDecimal interest,
        BigDecimal amortization,
        BigDecimal remainingBalance) {
}
