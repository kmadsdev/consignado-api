package io.github.kmadsdev.consignado.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstallmentRepository extends JpaRepository<Installment, UUID> {

    /** Uses uk_installment_contract_number. */
    List<Installment> findByContractIdOrderByNumber(UUID contractId);
}
