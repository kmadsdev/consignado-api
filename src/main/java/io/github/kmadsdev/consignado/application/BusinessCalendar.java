package io.github.kmadsdev.consignado.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Timestamps are stored in UTC; calendar dates (due dates) follow the business time zone.
 * The first installment is due one month after the reference date.
 */
public final class BusinessCalendar {

    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private BusinessCalendar() {
    }

    public static LocalDate firstDueDate(Instant reference) {
        return LocalDate.ofInstant(reference, ZONE).plusMonths(1);
    }
}
