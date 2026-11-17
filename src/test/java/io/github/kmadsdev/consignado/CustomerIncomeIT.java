package io.github.kmadsdev.consignado;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kmadsdev.consignado.testsupport.ApiTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

/** The one guarded update of shared data: PATCH /customers/{id}/income, end-to-end on PostgreSQL. */
class CustomerIncomeIT extends ApiTestBase {

    @Test
    void anIncomeChangeIsStoredAndMovesTheMargin() {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "5000.00");

        ResponseEntity<JsonNode> response = changeIncome(customer, "6000.00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(id(response)).isEqualTo(customer);
        assertThat(decimal(response.getBody().get("netMonthlySalary"))).isEqualByComparingTo("6000.00");
        assertThat(response.getBody().get("cpf").asText()).startsWith("***.");
        assertThat(salary(customer)).isEqualByComparingTo("6000.00");
        assertThat(decimal(get("/api/v1/customers/" + customer + "/margin").getBody().get("availableMargin")))
                .isEqualByComparingTo("2100.00");
    }

    @Test
    void anIncomeWhoseMarginLimitIsBelowTheCommittedInstallmentsIs422AndChangesNothing() {
        UUID customer = customerWithAnApprovedContract();

        ResponseEntity<JsonNode> response = changeIncome(customer, "2000.00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getBody().get("code").asText()).isEqualTo("income-below-committed-margin");
        assertThat(decimal(response.getBody().get("marginLimit"))).isEqualByComparingTo("700.00");
        assertThat(decimal(response.getBody().get("committedInstallments"))).isEqualByComparingTo("916.80");
        assertThat(salary(customer)).isEqualByComparingTo("5000.00");
    }

    @Test
    void theBoundaryIsTheCentWhereTheLimitEqualsTheCommittedInstallments() {
        // Committed 916.80. 35% of 2619.43 = 916.8005 -> 916.80 (fits); of 2619.42 = 916.797 -> 916.79 (does not).
        UUID customer = customerWithAnApprovedContract();

        assertThat(changeIncome(customer, "2619.42").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(changeIncome(customer, "2619.43").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(decimal(get("/api/v1/customers/" + customer + "/margin").getBody().get("availableMargin")))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void aProposalReservesNoMarginSoItDoesNotBlockALowerIncome() {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "5000.00");
        propose(id(simulate(customer, "10000.00", 12)), UUID.randomUUID().toString());

        assertThat(changeIncome(customer, "1000.00").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aSettledContractFreesTheMarginForALowerIncome() {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "5000.00");
        UUID contract = id(propose(id(simulate(customer, "10000.00", 12)), UUID.randomUUID().toString()));
        action(contract, "approve", null, null);
        action(contract, "activate", null, null);
        assertThat(changeIncome(customer, "1000.00").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        action(contract, "settle", null, null);

        assertThat(changeIncome(customer, "1000.00").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anUnknownCustomerIs404() {
        ResponseEntity<JsonNode> response = changeIncome(UUID.randomUUID(), "1000.00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo("not-found");
    }

    @Test
    void anIncomeCutAndAnApprovalAtTheSameTimeCannotBothPass() throws Exception {
        // Cap 1050.00 at 3000.00; the installment is about 611. At 1000.00 the cap is 350.00.
        // Whichever request locks the customer row first wins; the other one must see its effect.
        for (int round = 0; round < 5; round++) {
            UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "3000.00");
            UUID contract = id(propose(id(simulate(customer, "6666.00", 12)), UUID.randomUUID().toString()));

            List<HttpStatusCode> statuses = runConcurrently(List.of(
                    () -> action(contract, "approve", null, null).getStatusCode(),
                    () -> changeIncome(customer, "1000.00").getStatusCode()));

            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNPROCESSABLE_ENTITY);
            JsonNode margin = get("/api/v1/customers/" + customer + "/margin").getBody();
            BigDecimal limit = decimal(margin.get("availableMargin")).add(decimal(margin.get("committedInstallments")));
            assertThat(decimal(margin.get("committedInstallments"))).isLessThanOrEqualTo(limit);
        }
    }

    private UUID customerWithAnApprovedContract() {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "5000.00");
        UUID contract = id(propose(id(simulate(customer, "10000.00", 12)), UUID.randomUUID().toString()));
        assertThat(action(contract, "approve", null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        return customer;
    }

    private BigDecimal salary(UUID customer) {
        return decimal(get("/api/v1/customers/" + customer).getBody().get("netMonthlySalary"));
    }
}
