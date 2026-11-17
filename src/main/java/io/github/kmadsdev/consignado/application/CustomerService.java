package io.github.kmadsdev.consignado.application;

import io.github.kmadsdev.consignado.application.Views.CustomerView;
import io.github.kmadsdev.consignado.application.Views.MarginView;
import io.github.kmadsdev.consignado.application.Views.PageView;
import io.github.kmadsdev.consignado.domain.BusinessRuleViolationException;
import io.github.kmadsdev.consignado.domain.ContractStatus;
import io.github.kmadsdev.consignado.domain.Cpf;
import io.github.kmadsdev.consignado.domain.MarginPolicy;
import io.github.kmadsdev.consignado.persistence.Agreement;
import io.github.kmadsdev.consignado.persistence.AgreementRepository;
import io.github.kmadsdev.consignado.persistence.Customer;
import io.github.kmadsdev.consignado.persistence.CustomerRepository;
import io.github.kmadsdev.consignado.persistence.LoanContractRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerRepository customers;
    private final AgreementRepository agreements;
    private final LoanContractRepository contracts;
    private final Clock clock;

    public CustomerService(CustomerRepository customers, AgreementRepository agreements,
                           LoanContractRepository contracts, Clock clock) {
        this.customers = customers;
        this.agreements = agreements;
        this.contracts = contracts;
        this.clock = clock;
    }

    public record NewCustomer(String cpf, String fullName, UUID agreementId, BigDecimal netMonthlySalary) {
    }

    @Transactional
    public CustomerView create(NewCustomer command) {
        Cpf cpf = Cpf.of(command.cpf());
        if (!agreements.existsById(command.agreementId())) {
            throw new BusinessRuleViolationException("agreement-not-found", "The agreement does not exist");
        }
        if (customers.existsByCpf(cpf)) {
            // The message never carries the CPF: it ends up in logs and problem responses.
            throw new ConflictException("customer-already-exists", "A customer with this CPF already exists");
        }
        Customer customer = customers.save(new Customer(
                cpf, command.fullName().strip(), command.agreementId(), command.netMonthlySalary(), clock.instant()));
        return CustomerView.from(customer);
    }

    public CustomerView get(UUID id) {
        return CustomerView.from(find(id));
    }

    /**
     * The one update of shared data outside the contract workflow. It locks the customer
     * row like an approval does, so the two serialize: whichever comes second sees the
     * other's result, and the committed installments never end above the limit.
     */
    @Transactional
    public CustomerView changeIncome(UUID id, BigDecimal netMonthlySalary) {
        Customer customer = customers.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
        Agreement agreement = agreements.findById(customer.getAgreementId()).orElseThrow();
        BigDecimal committed = contracts.sumInstallments(id, ContractStatus.marginConsuming());
        MarginPolicy.requireCovers(
                MarginPolicy.marginLimit(netMonthlySalary, agreement.getMaxMarginPercent()), committed);
        customer.changeNetMonthlySalary(netMonthlySalary);
        return CustomerView.from(customers.saveAndFlush(customer));
    }

    public PageView<CustomerView> list(int page, int size) {
        return PageView.from(customers.findAll(Paging.newestFirst(page, size)), CustomerView::from);
    }

    public MarginView margin(UUID id) {
        Customer customer = find(id);
        Agreement agreement = agreements.findById(customer.getAgreementId()).orElseThrow();
        BigDecimal committed = contracts.sumInstallments(id, ContractStatus.marginConsuming());
        BigDecimal available = MarginPolicy.availableMargin(
                customer.getNetMonthlySalary(), agreement.getMaxMarginPercent(), committed);
        return new MarginView(id, customer.getNetMonthlySalary(), agreement.getMaxMarginPercent(),
                committed.setScale(2), available);
    }

    private Customer find(UUID id) {
        return customers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer", id));
    }
}
