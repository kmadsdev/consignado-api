package io.github.kmadsdev.consignado.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgreementRepository extends JpaRepository<Agreement, UUID> {

    boolean existsByCode(String code);
}
