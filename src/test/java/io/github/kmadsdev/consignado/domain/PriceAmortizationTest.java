package io.github.kmadsdev.consignado.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PriceAmortizationTest {

    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 1, 10);

    @ParameterizedTest(name = "P={0}, i={1}, n={2} -> PMT={3}")
    @CsvSource({
            "10000.00, 0.015,  12, 916.80",
            "1000.00,  0.01,   12, 88.85",
            "1000.00,  0.01,   3,  340.02",
            // (1.0185)^84 = 4.66386...; 15000 * 0.0185 * 4.66386 / 3.66386 = 353.24
            "15000.00, 0.0185, 84, 353.24",
    })
    void computesTheFixedInstallment(String principal, String rate, int term, String expected) {
        assertThat(PriceAmortization.installment(new BigDecimal(principal), new BigDecimal(rate), term))
                .isEqualByComparingTo(expected);
    }

    @Test
    void buildsAScheduleWhereTheLastInstallmentAbsorbsTheRoundingRemainder() {
        List<ScheduledInstallment> schedule =
                PriceAmortization.schedule(new BigDecimal("1000.00"), new BigDecimal("0.01"), 3, FIRST_DUE);

        // Hand-checked: interest on the balance rounded HALF_EVEN, last line closes the balance.
        assertThat(schedule).extracting(ScheduledInstallment::amount)
                .containsExactly(bd("340.02"), bd("340.02"), bd("340.03"));
        assertThat(schedule).extracting(ScheduledInstallment::interest)
                .containsExactly(bd("10.00"), bd("6.70"), bd("3.37"));
        assertThat(schedule).extracting(ScheduledInstallment::amortization)
                .containsExactly(bd("330.02"), bd("333.32"), bd("336.66"));
        assertThat(schedule).extracting(ScheduledInstallment::remainingBalance)
                .containsExactly(bd("669.98"), bd("336.66"), bd("0.00"));
        assertThat(PriceAmortization.totalPayable(schedule)).isEqualByComparingTo("1020.07");
    }

    @ParameterizedTest(name = "P={0}, i={1}, n={2}")
    @CsvSource({
            "1000.00,   0.01,    3",
            "5000.00,   0.0199,  24",
            "15000.00,  0.0185,  84",
            "50000.00,  0.0172,  96",
            "123456.78, 0.021,   120",
            "100.00,    0.0001,  420",
            "999.99,    0,       7",
    })
    void scheduleInvariantsHold(String principal, String rate, int term) {
        BigDecimal p = new BigDecimal(principal);
        BigDecimal i = new BigDecimal(rate);
        BigDecimal pmt = PriceAmortization.installment(p, i, term);

        List<ScheduledInstallment> schedule = PriceAmortization.schedule(p, i, term, FIRST_DUE);

        assertThat(schedule).hasSize(term);
        BigDecimal amortized = schedule.stream()
                .map(ScheduledInstallment::amortization)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(amortized).as("amortizations add up to the principal, to the cent").isEqualByComparingTo(p);
        assertThat(schedule.getLast().remainingBalance()).isEqualByComparingTo("0");
        assertThat(schedule).allSatisfy(line -> {
            assertThat(line.amount()).isEqualByComparingTo(line.interest().add(line.amortization()));
            assertThat(line.amount().scale()).isEqualTo(2);
            assertThat(line.remainingBalance().signum()).isNotNegative();
        });
        assertThat(schedule.subList(0, term - 1)).allSatisfy(line -> assertThat(line.amount()).isEqualByComparingTo(pmt));
        BigDecimal lastDiff = schedule.getLast().amount().subtract(pmt).abs();
        assertThat(lastDiff).as("remainder stays small").isLessThanOrEqualTo(new BigDecimal("0.01").multiply(BigDecimal.valueOf(term)));
    }

    @Test
    void usesBankersRoundingNotHalfUp() {
        // 100.50 * 1.01 = 101.505 exactly. HALF_UP gives 101.51, HALF_EVEN gives 101.50.
        BigDecimal pmt = PriceAmortization.installment(new BigDecimal("100.50"), new BigDecimal("0.01"), 1);

        assertThat(pmt).isEqualByComparingTo("101.50");
        ScheduledInstallment only = PriceAmortization
                .schedule(new BigDecimal("100.50"), new BigDecimal("0.01"), 1, FIRST_DUE).getFirst();
        assertThat(only.interest()).isEqualByComparingTo("1.00");
        assertThat(only.amount()).isEqualByComparingTo("101.50");
    }

    @Test
    void zeroRateSplitsThePrincipalAndTheLastInstallmentTakesTheCent() {
        List<ScheduledInstallment> schedule =
                PriceAmortization.schedule(new BigDecimal("1000.00"), BigDecimal.ZERO, 3, FIRST_DUE);

        assertThat(schedule).extracting(ScheduledInstallment::amount)
                .containsExactly(bd("333.33"), bd("333.33"), bd("333.34"));
    }

    @Test
    void dueDatesAreMonthlyAndKeepTheDayAfterAShortMonth() {
        List<ScheduledInstallment> schedule = PriceAmortization.schedule(
                new BigDecimal("1000.00"), new BigDecimal("0.01"), 3, LocalDate.of(2026, 1, 31));

        assertThat(schedule).extracting(ScheduledInstallment::dueDate).containsExactly(
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31));
    }

    @ParameterizedTest(name = "P={0}, i={1}, n={2} is rejected")
    @CsvSource({
            "0,       0.01, 12",
            "-100.00, 0.01, 12",
            "100.001, 0.01, 12",
            "1000.00, -0.01, 12",
            "1000.00, 1,    12",
            "1000.00, 0.01, 0",
            "1000.00, 0.01, 421",
    })
    void rejectsInvalidInput(String principal, String rate, int term) {
        assertThatThrownBy(() -> PriceAmortization.installment(new BigDecimal(principal), new BigDecimal(rate), term))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
