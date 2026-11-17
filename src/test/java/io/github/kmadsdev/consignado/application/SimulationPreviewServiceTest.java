package io.github.kmadsdev.consignado.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kmadsdev.consignado.application.SimulationPreviewService.PreviewInput;
import io.github.kmadsdev.consignado.application.Views.PreviewView;
import io.github.kmadsdev.consignado.domain.BusinessRuleViolationException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Plain unit test: the preview needs nothing but a clock. */
class SimulationPreviewServiceTest {

    private final SimulationPreviewService service = new SimulationPreviewService(
            Clock.fixed(Instant.parse("2026-03-02T13:00:00Z"), ZoneOffset.UTC));

    @Test
    void quotesWithThePriceScheduleAndTheRoundingOfTheSavedSimulation() {
        // The hand-checked schedule of PriceAmortizationTest: 340.02, 340.02, 340.03.
        PreviewView view = service.preview(input("1000.00", 3, "0.01", null, null));

        assertThat(view.principal()).isEqualTo(bd("1000.00"));
        assertThat(view.termMonths()).isEqualTo(3);
        assertThat(view.monthlyInterestRate()).isEqualByComparingTo("0.01");
        assertThat(view.installment()).isEqualTo(bd("340.02"));
        assertThat(view.totalAmount()).isEqualTo(bd("1020.07"));
        assertThat(view.totalInterest()).isEqualTo(bd("20.07"));
    }

    @Test
    void aPrincipalWithoutCentsLeavesWithTwoDecimalPlaces() {
        PreviewView view = service.preview(input("1000", 3, "0.01", null, null));

        assertThat(view.principal()).isEqualTo(bd("1000.00"));
        assertThat(view.totalInterest()).isEqualTo(bd("20.07"));
    }

    @Test
    void withoutIncomeThereIsNoMarginCheck() {
        PreviewView view = service.preview(input("10000.00", 12, "0.015", null, null));

        assertThat(view.installment()).isEqualTo(bd("916.80"));
        assertThat(view.marginLimit()).isNull();
        assertThat(view.withinMargin()).isNull();
    }

    @Test
    void theMarginLimitIsRoundedDownLikeTheRealMargin() {
        PreviewView view = service.preview(input("1000.00", 3, "0.01", "3333.33", "35"));

        assertThat(view.marginLimit()).isEqualTo(bd("1166.66"));
        assertThat(view.withinMargin()).isTrue();
    }

    @Test
    void theMarginCheckUsesTheLargestInstallmentNotOnlyThePmt() {
        // PMT 353.24, last line 354.04 (the case of ContractLifecycleIT). 35% of 1009.26 = 353.24.
        PreviewView tight = service.preview(input("15000.00", 84, "0.0185", "1009.26", "35"));
        // 35% of 1011.55 = 354.04: the last line fits exactly.
        PreviewView enough = service.preview(input("15000.00", 84, "0.0185", "1011.55", "35"));

        assertThat(tight.installment()).isEqualTo(bd("353.24"));
        assertThat(tight.marginLimit()).isEqualTo(bd("353.24"));
        assertThat(tight.withinMargin()).isFalse();
        assertThat(enough.marginLimit()).isEqualTo(bd("354.04"));
        assertThat(enough.withinMargin()).isTrue();
    }

    @Test
    void aZeroRateHasNoInterest() {
        PreviewView view = service.preview(input("1200.00", 12, "0", null, null));

        assertThat(view.installment()).isEqualTo(bd("100.00"));
        assertThat(view.totalAmount()).isEqualTo(bd("1200.00"));
        assertThat(view.totalInterest()).isEqualTo(bd("0.00"));
    }

    @Test
    void aTermThatPaysTheLoanOffEarlyIsTheSameBusinessErrorAsInASavedSimulation() {
        assertThatThrownBy(() -> service.preview(input("100.00", 420, "0", null, null)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("term-too-long-for-amount"));
    }

    private static PreviewInput input(String principal, int term, String rate, String income, String percent) {
        return new PreviewInput(bd(principal), term, bd(rate),
                income == null ? null : bd(income), percent == null ? null : bd(percent));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
