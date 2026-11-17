package io.github.kmadsdev.consignado.application;

import io.github.kmadsdev.consignado.application.Views.PageView;
import io.github.kmadsdev.consignado.application.Views.SimulationView;
import io.github.kmadsdev.consignado.domain.BusinessRuleViolationException;
import io.github.kmadsdev.consignado.domain.ContractStatus;
import io.github.kmadsdev.consignado.domain.MarginExceededException;
import io.github.kmadsdev.consignado.domain.MarginPolicy;
import io.github.kmadsdev.consignado.domain.PriceAmortization;
import io.github.kmadsdev.consignado.domain.ScheduledInstallment;
import io.github.kmadsdev.consignado.observability.BusinessMetrics;
import io.github.kmadsdev.consignado.persistence.Agreement;
import io.github.kmadsdev.consignado.persistence.AgreementRepository;
import io.github.kmadsdev.consignado.persistence.Customer;
import io.github.kmadsdev.consignado.persistence.CustomerRepository;
import io.github.kmadsdev.consignado.persistence.LoanContractRepository;
import io.github.kmadsdev.consignado.persistence.LoanSimulation;
import io.github.kmadsdev.consignado.persistence.LoanSimulationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SimulationService {

    private final CustomerRepository customers;
    private final AgreementRepository agreements;
    private final LoanContractRepository contracts;
    private final LoanSimulationRepository simulations;
    private final BusinessMetrics metrics;
    private final Clock clock;
    private final Duration validity;

    public SimulationService(CustomerRepository customers, AgreementRepository agreements,
                             LoanContractRepository contracts, LoanSimulationRepository simulations,
                             BusinessMetrics metrics, Clock clock,
                             @Value("${consignado.simulation.validity:P7D}") Duration validity) {
        this.customers = customers;
        this.agreements = agreements;
        this.contracts = contracts;
        this.simulations = simulations;
        this.metrics = metrics;
        this.clock = clock;
        this.validity = validity;
    }

    /**
     * Quotes a loan with the agreement's rate and checks it against the margin that is
     * free right now. A simulation does not reserve margin; approval does.
     */
    @Transactional
    public SimulationView simulate(UUID customerId, BigDecimal amount, int termMonths) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new BusinessRuleViolationException("customer-not-found", "The customer does not exist"));
        Agreement agreement = agreements.findById(customer.getAgreementId()).orElseThrow();
        if (termMonths > agreement.getMaxTermMonths()) {
            metrics.simulation("term_exceeded");
            throw new BusinessRuleViolationException("term-above-agreement-limit",
                    "The agreement allows at most " + agreement.getMaxTermMonths() + " months");
        }

        BigDecimal committed = contracts.sumInstallments(customerId, ContractStatus.marginConsuming());
        BigDecimal available = MarginPolicy.availableMargin(
                customer.getNetMonthlySalary(), agreement.getMaxMarginPercent(), committed);
        Instant now = clock.instant();
        BigDecimal installment = PriceAmortization.installment(amount, agreement.getMonthlyInterestRate(), termMonths);
        List<ScheduledInstallment> schedule = PriceAmortization.schedule(
                amount, agreement.getMonthlyInterestRate(), termMonths, BusinessCalendar.firstDueDate(now));
        try {
            // Not only PMT: the last installment can be larger when it absorbs the rounding remainder.
            MarginPolicy.requireFits(PriceAmortization.largestInstallment(schedule), available);
        } catch (MarginExceededException e) {
            metrics.simulation("margin_exceeded");
            throw e;
        }
        metrics.simulation("accepted");

        LoanSimulation simulation = simulations.save(new LoanSimulation(
                customerId, agreement.getId(), amount, agreement.getMonthlyInterestRate(), termMonths, installment,
                PriceAmortization.totalPayable(schedule), available, now, now.plus(validity)));
        return SimulationView.from(simulation, schedule);
    }

    public SimulationView get(UUID id) {
        LoanSimulation simulation = simulations.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Simulation", id));
        List<ScheduledInstallment> schedule = PriceAmortization.schedule(simulation.getPrincipal(),
                simulation.getMonthlyInterestRate(), simulation.getTermMonths(),
                BusinessCalendar.firstDueDate(simulation.getCreatedAt()));
        return SimulationView.from(simulation, schedule);
    }

    /** Summaries only: a page of 100 quotes with up to 420 schedule lines each is not a listing. */
    public PageView<SimulationView> list(int page, int size) {
        return PageView.from(simulations.findAll(Paging.newestFirst(page, size)), SimulationView::summary);
    }
}
