package io.github.kmadsdev.consignado.persistence;

import io.github.kmadsdev.consignado.domain.ContractStatus;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanContractRepository
        extends JpaRepository<LoanContract, UUID>, JpaSpecificationExecutor<LoanContract> {

    Optional<LoanContract> findByIdempotencyKey(String idempotencyKey);

    boolean existsBySimulationId(UUID simulationId);

    /** Uses ix_contract_customer_status. */
    @Query("""
            select coalesce(sum(c.installmentAmount), 0)
            from LoanContract c
            where c.customerId = :customerId and c.status in :statuses
            """)
    BigDecimal sumInstallments(@Param("customerId") UUID customerId,
                               @Param("statuses") Collection<ContractStatus> statuses);

    /** Optional filters for the listing endpoint. A null argument means "no filter". */
    static Specification<LoanContract> matching(UUID customerId, ContractStatus status) {
        Specification<LoanContract> spec = (root, query, cb) -> cb.conjunction();
        if (customerId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("customerId"), customerId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        return spec;
    }
}
