package io.github.kmadsdev.consignado.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rounding rules for amounts in BRL. All monetary values in the domain have 2 decimal
 * places and are rounded with banker's rounding (HALF_EVEN), so rounding errors do not
 * drift in one direction across many installments.
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    private Money() {
    }

    public static BigDecimal round(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }

    /** Rejects null, zero, negative and amounts with more than 2 decimal places. */
    public static BigDecimal requirePositiveAmount(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        if (value.stripTrailingZeros().scale() > SCALE) {
            throw new IllegalArgumentException(field + " must have at most 2 decimal places");
        }
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }
}
