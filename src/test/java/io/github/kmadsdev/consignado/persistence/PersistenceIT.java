package io.github.kmadsdev.consignado.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kmadsdev.consignado.domain.ContractStatus;
import io.github.kmadsdev.consignado.domain.Cpf;
import io.github.kmadsdev.consignado.domain.PriceAmortization;
import io.github.kmadsdev.consignado.testsupport.PostgresTestcontainer;
import io.github.kmadsdev.consignado.testsupport.TestCpfs;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Repository slice against PostgreSQL with the real Flyway migrations. Hibernate runs
 * with ddl-auto=validate, so this test also fails if a mapping drifts from the schema.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainer.class)
class PersistenceIT {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    @Autowired
    private TestEntityManager em;
    @Autowired
    private AgreementRepository agreements;
    @Autowired
    private CustomerRepository customers;
    @Autowired
    private LoanSimulationRepository simulations;
    @Autowired
    private LoanContractRepository contracts;
    @Autowired
    private InstallmentRepository installments;
    @Autowired
    private EntityManagerFactory emf;

    private Customer customer;

    @BeforeEach
    void setUp() {
        Agreement agreement = agreements.save(new Agreement(
                "AG-" + UUID.randomUUID().toString().substring(0, 8), "Test agreement",
                new BigDecimal("35.00"), 96, new BigDecimal("0.018500"), NOW));
        customer = customers.save(new Customer(
                Cpf.of(TestCpfs.random()), "Test Customer", agreement.getId(), new BigDecimal("5000.00"), NOW));
    }

    @Test
    void storesTheCpfAsDigitsAndReadsItBackThroughTheConverter() {
        em.flush();
        em.clear();

        Customer loaded = customers.findById(customer.getId()).orElseThrow();
        assertThat(loaded.getCpf()).isEqualTo(customer.getCpf());
        assertThat(customers.existsByCpf(customer.getCpf())).isTrue();
    }

    @Test
    void cpfIsUnique() {
        Customer duplicate = new Customer(
                customer.getCpf(), "Someone Else", customer.getAgreementId(), new BigDecimal("1000.00"), NOW);

        assertThatThrownBy(() -> customers.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sumOfInstallmentsCountsOnlyMarginConsumingStatuses() {
        contractIn(ContractStatus.PROPOSED, "300.00");
        contractIn(ContractStatus.APPROVED, "200.00");
        contractIn(ContractStatus.ACTIVE, "150.50");
        contractIn(ContractStatus.CANCELLED, "999.00");

        BigDecimal committed = contracts.sumInstallments(customer.getId(), ContractStatus.marginConsuming());

        assertThat(committed).isEqualByComparingTo("350.50");
    }

    @Test
    void sumIsZeroWhenTheCustomerHasNoContracts() {
        assertThat(contracts.sumInstallments(customer.getId(), EnumSet.allOf(ContractStatus.class)))
                .isEqualByComparingTo("0");
    }

    @Test
    void idempotencyKeyIsUnique() {
        LoanContract first = LoanContract.propose(simulation("100.00"), "same-key-123", NOW);
        contracts.saveAndFlush(first);

        LoanContract second = LoanContract.propose(simulation("100.00"), "same-key-123", NOW);

        assertThatThrownBy(() -> contracts.saveAndFlush(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSimulationBecomesAtMostOneContract() {
        LoanSimulation simulation = simulation("100.00");
        contracts.saveAndFlush(LoanContract.propose(simulation, "key-one-123", NOW));

        LoanContract again = LoanContract.propose(simulation, "key-two-123", NOW);

        assertThat(contracts.existsBySimulationId(simulation.getId())).isTrue();
        assertThatThrownBy(() -> contracts.saveAndFlush(again)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void staleUpdateFailsWithOptimisticLocking() {
        LoanContract saved = contracts.saveAndFlush(LoanContract.propose(simulation("100.00"), "key-lock-123", NOW));
        em.clear();
        LoanContract staleCopy = contracts.findById(saved.getId()).orElseThrow();
        em.clear();

        LoanContract fresh = contracts.findById(saved.getId()).orElseThrow();
        fresh.moveTo(ContractStatus.APPROVED, null, NOW);
        contracts.saveAndFlush(fresh);
        em.clear();

        staleCopy.moveTo(ContractStatus.REJECTED, "stale", NOW);
        assertThatThrownBy(() -> contracts.saveAndFlush(staleCopy))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void listsWithOptionalFiltersAndPagination() {
        contractIn(ContractStatus.PROPOSED, "100.00");
        contractIn(ContractStatus.PROPOSED, "110.00");
        contractIn(ContractStatus.APPROVED, "120.00");
        PageRequest firstPage = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<LoanContract> proposed = contracts.findAll(
                LoanContractRepository.matching(customer.getId(), ContractStatus.PROPOSED), firstPage);
        Page<LoanContract> all = contracts.findAll(LoanContractRepository.matching(customer.getId(), null), firstPage);
        Page<LoanContract> unfiltered = contracts.findAll(LoanContractRepository.matching(null, null), firstPage);

        assertThat(proposed.getTotalElements()).isEqualTo(2);
        assertThat(proposed.getContent()).hasSize(1);
        assertThat(all.getTotalElements()).isEqualTo(3);
        assertThat(unfiltered.getTotalElements()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void installmentsComeBackInOrderAndTheDatabaseChecksAmountEqualsInterestPlusAmortization() {
        LoanContract contract = contractIn(ContractStatus.ACTIVE, "340.02");
        var schedule = PriceAmortization.schedule(
                new BigDecimal("1000.00"), new BigDecimal("0.01"), 3, LocalDate.of(2026, 4, 1));
        installments.saveAll(schedule.reversed().stream().map(line -> Installment.of(contract.getId(), line)).toList());
        em.flush();
        em.clear();

        List<Installment> loaded = installments.findByContractIdOrderByNumber(contract.getId());

        assertThat(loaded).extracting(Installment::getNumber).containsExactly(1, 2, 3);
        assertThat(loaded.getLast().getRemainingBalance()).isEqualByComparingTo("0");
    }

    @Test
    void savingNewRowsWithAssignedIdsIssuesOnlyInserts() {
        LoanContract contract = contractIn(ContractStatus.ACTIVE, "340.02");
        var schedule = PriceAmortization.schedule(
                new BigDecimal("1000.00"), new BigDecimal("0.01"), 3, LocalDate.of(2026, 4, 1));
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        em.flush();
        statistics.clear();

        installments.saveAll(schedule.stream().map(line -> Installment.of(contract.getId(), line)).toList());
        simulation("100.00");
        em.flush();

        // Without Persistable, Spring Data sees an id and calls merge(): one SELECT per row first.
        assertThat(statistics.getPrepareStatementCount()).as("3 installments + 1 simulation, inserts only")
                .isEqualTo(4);
    }

    private LoanSimulation simulation(String installment) {
        return simulations.save(new LoanSimulation(
                customer.getId(), customer.getAgreementId(), new BigDecimal("1000.00"), new BigDecimal("0.010000"),
                3, new BigDecimal(installment), new BigDecimal("1020.07"), new BigDecimal("1750.00"),
                NOW, NOW.plus(7, ChronoUnit.DAYS)));
    }

    private LoanContract contractIn(ContractStatus status, String installment) {
        LoanContract contract = LoanContract.propose(simulation(installment), UUID.randomUUID().toString(), NOW);
        switch (status) {
            case PROPOSED -> { }
            case APPROVED -> contract.moveTo(ContractStatus.APPROVED, null, NOW);
            case ACTIVE -> {
                contract.moveTo(ContractStatus.APPROVED, null, NOW);
                contract.moveTo(ContractStatus.ACTIVE, null, NOW);
            }
            case CANCELLED -> contract.moveTo(ContractStatus.CANCELLED, "test", NOW);
            default -> throw new IllegalArgumentException("not used in these tests: " + status);
        }
        return contracts.saveAndFlush(contract);
    }
}
