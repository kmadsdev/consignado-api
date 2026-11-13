package io.github.kmadsdev.consignado.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MarginPolicyTest {

    @ParameterizedTest(name = "salary={0}, margin={1}%, committed={2} -> available={3}")
    @CsvSource({
            "5000.00, 35,    0,       1750.00",
            "5000.00, 35,    500.00,  1250.00",
            "5000.00, 35,    1750.00, 0.00",
            "5000.00, 35,    2000.00, 0.00",
            // 3333.33 * 0.35 = 1166.6655: HALF_EVEN would give 1166.67, the cap must be 1166.66.
            "3333.33, 35,    0,       1166.66",
            "2500.00, 45.00, 0,       1125.00",
    })
    void computesAvailableMarginRoundingTheCapDown(
            String salary, String percent, String committed, String expected) {
        assertThat(MarginPolicy.availableMargin(bd(salary), bd(percent), bd(committed)))
                .isEqualByComparingTo(expected)
                .satisfies(value -> assertThat(value.scale()).isEqualTo(2));
    }

    @Test
    void treatsNullCommittedAsZero() {
        assertThat(MarginPolicy.availableMargin(bd("1000.00"), bd("30"), null)).isEqualByComparingTo("300.00");
    }

    @Test
    void anInstallmentEqualToTheMarginFits() {
        assertThatCode(() -> MarginPolicy.requireFits(bd("1750.00"), bd("1750.00"))).doesNotThrowAnyException();
    }

    @Test
    void oneCentAboveTheMarginIsRejectedWithBothValues() {
        assertThatThrownBy(() -> MarginPolicy.requireFits(bd("1750.01"), bd("1750.00")))
                .isInstanceOfSatisfying(MarginExceededException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("margin-exceeded");
                    assertThat(ex.availableMargin()).isEqualByComparingTo("1750.00");
                    assertThat(ex.installment()).isEqualByComparingTo("1750.01");
                });
    }

    @ParameterizedTest
    @CsvSource({"0, 35", "-1, 35", "1000, 0", "1000, 100.01"})
    void rejectsInvalidSalaryOrPercent(String salary, String percent) {
        assertThatThrownBy(() -> MarginPolicy.availableMargin(bd(salary), bd(percent), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
