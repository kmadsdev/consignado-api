package io.github.kmadsdev.consignado.application;

import io.github.kmadsdev.consignado.application.Views.ContractView;
import io.github.kmadsdev.consignado.application.Views.InstallmentView;
import io.github.kmadsdev.consignado.application.Views.PageView;
import io.github.kmadsdev.consignado.application.Views.ProposalResult;
import io.github.kmadsdev.consignado.domain.BusinessRuleViolationException;
import io.github.kmadsdev.consignado.domain.ContractStatus;
import io.github.kmadsdev.consignado.domain.IllegalStatusTransitionException;
import io.github.kmadsdev.consignado.domain.MarginPolicy;
import io.github.kmadsdev.consignado.domain.PriceAmortization;
import io.github.kmadsdev.consignado.observability.BusinessMetrics;
import io.github.kmadsdev.consignado.persistence.Agreement;
import io.github.kmadsdev.consignado.persistence.AgreementRepository;
import io.github.kmadsdev.consignado.persistence.Customer;
import io.github.kmadsdev.consignado.persistence.CustomerRepository;
import io.github.kmadsdev.consignado.persistence.Installment;
import io.github.kmadsdev.consignado.persistence.InstallmentRepository;
import io.github.kmadsdev.consignado.persistence.LoanContract;
import io.github.kmadsdev.consignado.persistence.LoanContractRepository;
import io.github.kmadsdev.consignado.persistence.LoanSimulation;
import io.github.kmadsdev.consignado.persistence.LoanSimulationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ContractService {

    private final LoanContractRepository contracts;
    private final LoanSimulationRepository simulations;
    private final CustomerRepository customers;
    private final AgreementRepository agreements;
    private final InstallmentRepository installments;
    private final ContractEvents events;
    private final BusinessMetrics metrics;
    private final TransactionTemplate tx;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public ContractService(LoanContractRepository contracts, LoanSimulationRepository simulations,
                           CustomerRepository customers, AgreementRepository agreements,
                           InstallmentRepository installments, ContractEvents events, BusinessMetrics metrics,
                           TransactionTemplate tx, Clock clock) {
        this.contracts = contracts;
        this.simulations = simulations;
        this.customers = customers;
        this.agreements = agreements;
        this.installments = installments;
        this.events = events;
        this.metrics = metrics;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Creates a proposal from a simulation. Safe to retry with the same key:
     * <ol>
     *   <li>Key already stored: return that contract (or 422 if the body differs).</li>
     *   <li>Otherwise insert in its own transaction. Two concurrent requests with the same
     *       key both reach the insert; the unique constraint lets one win, and the loser
     *       reads the winner's row and returns it.</li>
     * </ol>
     */
    public ProposalResult propose(String idempotencyKey, UUID simulationId) {
        var existing = contracts.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), simulationId);
        }
        try {
            ContractView created = tx.execute(status -> insertProposal(idempotencyKey, simulationId));
            return new ProposalResult(created, true);
        } catch (DataIntegrityViolationException | ConflictException raceLost) {
            // A concurrent request with the same key may have committed first. Depending on
            // timing we see it as a unique-key violation or as "simulation already used".
            return contracts.findByIdempotencyKey(idempotencyKey)
                    .map(winner -> replay(winner, simulationId))
                    .orElseThrow(() -> raceLost instanceof ConflictException conflict ? conflict : simulationAlreadyUsed());
        }
    }

    @Transactional
    public ContractView approve(UUID id, Long expectedVersion) {
        LoanContract contract = load(id, expectedVersion);
        requireTransition(contract, ContractStatus.APPROVED);

        // Serialize margin checks per customer: lock the row, then read and decide.
        Customer customer = customers.findByIdForUpdate(contract.getCustomerId()).orElseThrow();
        Agreement agreement = agreements.findById(customer.getAgreementId()).orElseThrow();
        BigDecimal committed = contracts.sumInstallments(customer.getId(), ContractStatus.marginConsuming());
        BigDecimal available = MarginPolicy.availableMargin(
                customer.getNetMonthlySalary(), agreement.getMaxMarginPercent(), committed);
        var schedule = PriceAmortization.schedule(contract.getPrincipal(), contract.getMonthlyInterestRate(),
                contract.getTermMonths(), BusinessCalendar.firstDueDate(clock.instant()));
        MarginPolicy.requireFits(PriceAmortization.largestInstallment(schedule), available);

        return transition(contract, ContractStatus.APPROVED, null);
    }

    @Transactional
    public ContractView reject(UUID id, String reason, Long expectedVersion) {
        return transition(load(id, expectedVersion), ContractStatus.REJECTED, reason);
    }

    /** Activation writes the definitive schedule, due dates counted from today. */
    @Transactional
    public ContractView activate(UUID id, Long expectedVersion) {
        LoanContract contract = load(id, expectedVersion);
        ContractView view = transition(contract, ContractStatus.ACTIVE, null);
        var schedule = PriceAmortization.schedule(contract.getPrincipal(), contract.getMonthlyInterestRate(),
                contract.getTermMonths(), BusinessCalendar.firstDueDate(clock.instant()));
        installments.saveAll(schedule.stream().map(line -> Installment.of(contract.getId(), line)).toList());
        return view;
    }

    @Transactional
    public ContractView settle(UUID id, Long expectedVersion) {
        return transition(load(id, expectedVersion), ContractStatus.SETTLED, null);
    }

    @Transactional
    public ContractView cancel(UUID id, String reason, Long expectedVersion) {
        return transition(load(id, expectedVersion), ContractStatus.CANCELLED, reason);
    }

    @Transactional(readOnly = true)
    public ContractView get(UUID id) {
        return ContractView.from(find(id));
    }

    @Transactional(readOnly = true)
    public PageView<ContractView> list(UUID customerId, ContractStatus status, int page, int size) {
        var result = contracts.findAll(
                LoanContractRepository.matching(customerId, status), Paging.newestFirst(page, size));
        return PageView.from(result, ContractView::from);
    }

    @Transactional(readOnly = true)
    public List<InstallmentView> installments(UUID id) {
        find(id);
        return installments.findByContractIdOrderByNumber(id).stream().map(InstallmentView::from).toList();
    }

    private ContractView insertProposal(String idempotencyKey, UUID simulationId) {
        LoanSimulation simulation = simulations.findById(simulationId)
                .orElseThrow(() -> new BusinessRuleViolationException("simulation-not-found",
                        "The simulation does not exist"));
        Instant now = clock.instant();
        if (simulation.isExpiredAt(now)) {
            throw new BusinessRuleViolationException("simulation-expired", "The simulation expired; simulate again");
        }
        if (contracts.existsBySimulationId(simulationId)) {
            throw simulationAlreadyUsed();
        }
        LoanContract contract = contracts.saveAndFlush(LoanContract.propose(simulation, idempotencyKey, now));
        events.statusChanged(contract, ContractStatus.SIMULATED);
        metrics.contractTransition(ContractStatus.SIMULATED, ContractStatus.PROPOSED);
        return ContractView.from(contract);
    }

    private ProposalResult replay(LoanContract contract, UUID simulationId) {
        if (!contract.getSimulationId().equals(simulationId)) {
            throw new IdempotencyKeyReuseException();
        }
        return new ProposalResult(ContractView.from(contract), false);
    }

    private ContractView transition(LoanContract contract, ContractStatus target, String reason) {
        ContractStatus previous = contract.moveTo(target, reason, clock.instant());
        // Flush now so a concurrent update fails here, inside the repository's exception translation.
        LoanContract saved = contracts.saveAndFlush(contract);
        // Same transaction as the status change: both commit or neither does.
        events.statusChanged(saved, previous);
        metrics.contractTransition(previous, target);
        return ContractView.from(saved);
    }

    private static void requireTransition(LoanContract contract, ContractStatus target) {
        if (!contract.getStatus().canTransitionTo(target)) {
            throw new IllegalStatusTransitionException(contract.getStatus(), target);
        }
    }

    private LoanContract load(UUID id, Long expectedVersion) {
        LoanContract contract = find(id);
        if (expectedVersion != null && !expectedVersion.equals(contract.getVersion())) {
            throw new VersionMismatchException(expectedVersion, contract.getVersion());
        }
        return contract;
    }

    private LoanContract find(UUID id) {
        return contracts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Contract", id));
    }

    private static ConflictException simulationAlreadyUsed() {
        return new ConflictException("simulation-already-used", "This simulation already became a contract");
    }
}
