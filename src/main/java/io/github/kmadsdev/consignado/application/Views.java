package io.github.kmadsdev.consignado.application;

import io.github.kmadsdev.consignado.domain.ContractStatus;
import io.github.kmadsdev.consignado.domain.ScheduledInstallment;
import io.github.kmadsdev.consignado.persistence.Agreement;
import io.github.kmadsdev.consignado.persistence.Customer;
import io.github.kmadsdev.consignado.persistence.Installment;
import io.github.kmadsdev.consignado.persistence.LoanContract;
import io.github.kmadsdev.consignado.persistence.LoanSimulation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Read models returned by the use cases. Entities never leave the application layer, so
 * the API cannot trigger lazy loading or serialize a field by accident.
 */
public final class Views {

    private Views() {
    }

    public record AgreementView(UUID id, String code, String name, BigDecimal maxMarginPercent,
                                int maxTermMonths, BigDecimal monthlyInterestRate, Instant createdAt) {
        static AgreementView from(Agreement a) {
            return new AgreementView(a.getId(), a.getCode(), a.getName(), a.getMaxMarginPercent(),
                    a.getMaxTermMonths(), a.getMonthlyInterestRate(), a.getCreatedAt());
        }
    }

    /** The CPF is always masked on the way out. */
    public record CustomerView(UUID id, String cpf, String fullName, UUID agreementId,
                               BigDecimal netMonthlySalary, Instant createdAt) {
        static CustomerView from(Customer c) {
            return new CustomerView(c.getId(), c.getCpf().masked(), c.getFullName(), c.getAgreementId(),
                    c.getNetMonthlySalary(), c.getCreatedAt());
        }
    }

    public record MarginView(UUID customerId, BigDecimal netMonthlySalary, BigDecimal maxMarginPercent,
                             BigDecimal committedInstallments, BigDecimal availableMargin) {
    }

    public record InstallmentView(int number, LocalDate dueDate, BigDecimal amount, BigDecimal interest,
                                  BigDecimal amortization, BigDecimal remainingBalance) {
        static InstallmentView from(ScheduledInstallment s) {
            return new InstallmentView(s.number(), s.dueDate(), s.amount(), s.interest(), s.amortization(),
                    s.remainingBalance());
        }

        static InstallmentView from(Installment i) {
            return new InstallmentView(i.getNumber(), i.getDueDate(), i.getAmount(), i.getInterest(),
                    i.getAmortization(), i.getRemainingBalance());
        }
    }

    public record SimulationView(UUID id, UUID customerId, UUID agreementId, BigDecimal principal,
                                 BigDecimal monthlyInterestRate, int termMonths, BigDecimal installmentAmount,
                                 BigDecimal totalPayable, BigDecimal availableMargin, Instant createdAt,
                                 Instant expiresAt, List<InstallmentView> schedule) {
        static SimulationView from(LoanSimulation s, List<ScheduledInstallment> schedule) {
            return new SimulationView(s.getId(), s.getCustomerId(), s.getAgreementId(), s.getPrincipal(),
                    s.getMonthlyInterestRate(), s.getTermMonths(), s.getInstallmentAmount(), s.getTotalPayable(),
                    s.getAvailableMargin(), s.getCreatedAt(), s.getExpiresAt(),
                    schedule.stream().map(InstallmentView::from).toList());
        }

        /** For listings. The schedule is null, so it is left out of the JSON (non_null inclusion). */
        static SimulationView summary(LoanSimulation s) {
            return new SimulationView(s.getId(), s.getCustomerId(), s.getAgreementId(), s.getPrincipal(),
                    s.getMonthlyInterestRate(), s.getTermMonths(), s.getInstallmentAmount(), s.getTotalPayable(),
                    s.getAvailableMargin(), s.getCreatedAt(), s.getExpiresAt(), null);
        }
    }

    public record ContractView(UUID id, UUID simulationId, UUID customerId, ContractStatus status,
                               BigDecimal principal, BigDecimal monthlyInterestRate, int termMonths,
                               BigDecimal installmentAmount, String statusReason, long version,
                               Instant createdAt, Instant updatedAt) {
        static ContractView from(LoanContract c) {
            return new ContractView(c.getId(), c.getSimulationId(), c.getCustomerId(), c.getStatus(),
                    c.getPrincipal(), c.getMonthlyInterestRate(), c.getTermMonths(), c.getInstallmentAmount(),
                    c.getStatusReason(), c.getVersion(), c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    /** {@code created} is false when the call replayed an earlier request with the same key. */
    public record ProposalResult(ContractView contract, boolean created) {
    }

    public record PageView<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        static <E, T> PageView<T> from(Page<E> page, Function<E, T> mapper) {
            return new PageView<>(page.getContent().stream().map(mapper).toList(), page.getNumber(),
                    page.getSize(), page.getTotalElements(), page.getTotalPages());
        }
    }
}
