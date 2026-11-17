package io.github.kmadsdev.consignado;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kmadsdev.consignado.testsupport.ApiTestBase;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The preview against the full application: same numbers as a saved simulation, and nothing saved. */
class SimulationPreviewIT extends ApiTestBase {

    @Test
    void aPreviewGivesTheNumbersOfTheSavedSimulationAndStoresNothing() {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.0185"), "5000.00");
        JsonNode saved = simulate(customer, "15000.00", 84).getBody();
        long simulationsBefore = get("/api/v1/simulations?size=1").getBody().get("totalElements").asLong();

        ResponseEntity<JsonNode> preview = post("/api/v1/simulations/preview", Map.of(
                "principal", new BigDecimal("15000.00"), "termMonths", 84,
                "monthlyInterestRate", new BigDecimal("0.0185"),
                "monthlyIncome", new BigDecimal("5000.00"), "maxMarginPercent", new BigDecimal("35.00")));

        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = preview.getBody();
        assertThat(decimal(body.get("installment"))).isEqualByComparingTo(decimal(saved.get("installmentAmount")));
        assertThat(decimal(body.get("totalAmount"))).isEqualByComparingTo(decimal(saved.get("totalPayable")));
        assertThat(decimal(body.get("marginLimit"))).isEqualByComparingTo(decimal(saved.get("availableMargin")));
        assertThat(body.get("withinMargin").asBoolean()).isTrue();
        assertThat(get("/api/v1/simulations?size=1").getBody().get("totalElements").asLong())
                .isEqualTo(simulationsBefore);
    }
}
