package io.github.kmadsdev.consignado.application;

import io.github.kmadsdev.consignado.application.Views.PreviewView;
import io.github.kmadsdev.consignado.domain.MarginPolicy;
import io.github.kmadsdev.consignado.domain.Money;
import io.github.kmadsdev.consignado.domain.PriceAmortization;
import io.github.kmadsdev.consignado.domain.ScheduledInstallment;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * A quote for a visitor who has no account: the same Price schedule and rounding as
 * {@link SimulationService#simulate}, with the rate and the margin inputs taken from the
 * request instead of an agreement and a customer.
 *
 * <p>Stateless on purpose. It has no repository and no transaction, so the one endpoint
 * that a guest may call cannot read or write the database. {@code ArchitectureTest} and
 * {@code SimulationPreviewTest} fail the build if that changes.
 */
@Service
public class SimulationPreviewService {

    private final Clock clock;

    public SimulationPreviewService(Clock clock) {
        this.clock = clock;
    }

    /** {@code monthlyIncome} and {@code maxMarginPercent} are both set or both null. */
    public record PreviewInput(BigDecimal principal, int termMonths, BigDecimal monthlyInterestRate,
                               BigDecimal monthlyIncome, BigDecimal maxMarginPercent) {
    }

    public PreviewView preview(PreviewInput input) {
        BigDecimal installment = PriceAmortization.installment(
                input.principal(), input.monthlyInterestRate(), input.termMonths());
        List<ScheduledInstallment> schedule = PriceAmortization.schedule(input.principal(),
                input.monthlyInterestRate(), input.termMonths(), BusinessCalendar.firstDueDate(clock.instant()));
        BigDecimal principal = Money.round(input.principal());
        BigDecimal totalAmount = PriceAmortization.totalPayable(schedule);

        BigDecimal marginLimit = null;
        Boolean withinMargin = null;
        if (input.monthlyIncome() != null && input.maxMarginPercent() != null) {
            // A guest has no contracts, so the whole limit is free. As in a saved simulation,
            // the largest installment must fit, not only PMT.
            marginLimit = MarginPolicy.marginLimit(input.monthlyIncome(), input.maxMarginPercent());
            withinMargin = PriceAmortization.largestInstallment(schedule).compareTo(marginLimit) <= 0;
        }
        return new PreviewView(principal, input.termMonths(), input.monthlyInterestRate(), installment,
                totalAmount, totalAmount.subtract(principal), marginLimit, withinMargin);
    }
}
