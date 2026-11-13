package io.github.kmadsdev.consignado.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanSimulationRepository extends JpaRepository<LoanSimulation, UUID> {
}
