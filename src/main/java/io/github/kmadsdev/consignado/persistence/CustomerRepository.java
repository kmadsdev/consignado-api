package io.github.kmadsdev.consignado.persistence;

import io.github.kmadsdev.consignado.domain.Cpf;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    boolean existsByCpf(Cpf cpf);

    /**
     * {@code select ... for update} on the customer row. Approving a contract reads the
     * customer's committed installments and then adds one; without this lock two
     * approvals for the same customer could both pass the margin check (write skew).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> findByIdForUpdate(@Param("id") UUID id);
}
