package io.github.kmadsdev.consignado;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kmadsdev.consignado.testsupport.ApiTestBase;
import io.github.kmadsdev.consignado.testsupport.TestCpfs;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

/** End-to-end over HTTP against PostgreSQL. */
class ContractLifecycleIT extends ApiTestBase {

    @Test
    void fullLifecycleFromSimulationToSettlement() {
        UUID agreement = createAgreement("35.00", 96, "0.015");
        UUID customer = createCustomer(agreement, "5000.00");

        ResponseEntity<JsonNode> simulation = simulate(customer, "10000.00", 12);
        assertThat(simulation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(decimal(simulation.getBody().get("installmentAmount"))).isEqualByComparingTo("916.80");
        assertThat(decimal(simulation.getBody().get("availableMargin"))).isEqualByComparingTo("1750.00");
        assertThat(simulation.getBody().get("schedule")).hasSize(12);

        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> proposed = propose(id(simulation), key);
        assertThat(proposed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(proposed.getBody().get("status").asText()).isEqualTo("PROPOSED");
        assertThat(proposed.getHeaders().getLocation()).isNotNull();
        UUID contract = id(proposed);

        ResponseEntity<JsonNode> approved = action(contract, "approve", proposed.getHeaders().getETag(), null);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().get("status").asText()).isEqualTo("APPROVED");

        JsonNode margin = get("/api/v1/customers/" + customer + "/margin").getBody();
        assertThat(decimal(margin.get("committedInstallments"))).isEqualByComparingTo("916.80");
        assertThat(decimal(margin.get("availableMargin"))).isEqualByComparingTo("833.20");

        ResponseEntity<JsonNode> active = action(contract, "activate", approved.getHeaders().getETag(), null);
        assertThat(active.getBody().get("status").asText()).isEqualTo("ACTIVE");

        JsonNode installments = get("/api/v1/contracts/" + contract + "/installments").getBody();
        assertThat(installments).hasSize(12);
        BigDecimal amortized = BigDecimal.ZERO;
        for (JsonNode line : installments) {
            amortized = amortized.add(decimal(line.get("amortization")));
        }
        assertThat(amortized).isEqualByComparingTo("10000.00");
        assertThat(installments.get(0).get("dueDate").asText()).isEqualTo("2026-04-02");

        ResponseEntity<JsonNode> settled = action(contract, "settle", null, null);
        assertThat(settled.getBody().get("status").asText()).isEqualTo("SETTLED");
        assertThat(decimal(get("/api/v1/customers/" + customer + "/margin").getBody().get("availableMargin")))
                .isEqualByComparingTo("1750.00");
    }

    @Test
    void simulationAboveTheMarginIs422WithTheAvailableMargin() {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "1000.00");

        ResponseEntity<JsonNode> response = simulate(customer, "10000.00", 12);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("margin-exceeded");
        assertThat(decimal(response.getBody().get("availableMargin"))).isEqualByComparingTo("350.00");
    }

    @Test
    void termAboveTheAgreementLimitIs422() {
        UUID customer = createCustomer(createAgreement("35.00", 24, "0.015"), "10000.00");

        ResponseEntity<JsonNode> response = simulate(customer, "1000.00", 36);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("term-above-agreement-limit");
    }

    @Test
    void approvalChecksTheMarginAgainBecauseOtherContractsMayHaveTakenIt() {
        // 35% of 3000 = 1050. Each installment (~611) fits alone; two do not.
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "3000.00");
        UUID first = id(propose(id(simulate(customer, "6666.00", 12)), UUID.randomUUID().toString()));
        UUID second = id(propose(id(simulate(customer, "6666.00", 12)), UUID.randomUUID().toString()));

        assertThat(action(first, "approve", null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> secondApproval = action(second, "approve", null, null);

        assertThat(secondApproval.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(secondApproval.getBody().get("code").asText()).isEqualTo("margin-exceeded");
    }

    @Test
    void concurrentApprovalsForTheSameCustomerCannotBothConsumeTheMargin() throws Exception {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "3000.00");
        UUID first = id(propose(id(simulate(customer, "6666.00", 12)), UUID.randomUUID().toString()));
        UUID second = id(propose(id(simulate(customer, "6666.00", 12)), UUID.randomUUID().toString()));

        List<HttpStatusCode> statuses = runConcurrently(List.of(
                () -> action(first, "approve", null, null).getStatusCode(),
                () -> action(second, "approve", null, null).getStatusCode()));

        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameContract() {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "5000.00");
        UUID simulation = id(simulate(customer, "1000.00", 12));
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = propose(simulation, key);
        ResponseEntity<JsonNode> again = propose(simulation, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(id(again)).isEqualTo(id(first));
    }

    @Test
    void concurrentRequestsWithOneKeyCreateExactlyOneContract() throws Exception {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "5000.00");
        UUID simulation = id(simulate(customer, "1000.00", 12));
        String key = UUID.randomUUID().toString();

        List<Callable<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> propose(simulation, key));
        }
        List<ResponseEntity<JsonNode>> responses = runConcurrently(calls);

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsOnly(HttpStatus.CREATED, HttpStatus.OK)
                .filteredOn(HttpStatus.CREATED::equals).hasSize(1);
        assertThat(responses).extracting(r -> id(r)).containsOnly(id(responses.getFirst()));
        assertThat(get("/api/v1/contracts?customerId=" + customer).getBody().get("totalElements").asInt())
                .isEqualTo(1);
    }

    @Test
    void reusingAKeyForAnotherSimulationIs422() {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "5000.00");
        String key = UUID.randomUUID().toString();
        propose(id(simulate(customer, "1000.00", 12)), key);

        ResponseEntity<JsonNode> response = propose(id(simulate(customer, "2000.00", 12)), key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("idempotency-key-reused");
    }

    @Test
    void aSimulationCannotBecomeTwoContracts() {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "5000.00");
        UUID simulation = id(simulate(customer, "1000.00", 12));
        propose(simulation, UUID.randomUUID().toString());

        ResponseEntity<JsonNode> response = propose(simulation, UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("simulation-already-used");
    }

    @Test
    void expiredSimulationCannotBeProposed() {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "5000.00");
        UUID simulation = id(simulate(customer, "1000.00", 12));
        clock.advance(Duration.ofDays(7));

        ResponseEntity<JsonNode> response = propose(simulation, UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("simulation-expired");
    }

    @Test
    void illegalTransitionIs409AndStaleIfMatchIs412() {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "5000.00");
        ResponseEntity<JsonNode> proposed = propose(id(simulate(customer, "1000.00", 12)), UUID.randomUUID().toString());
        UUID contract = id(proposed);

        ResponseEntity<JsonNode> activate = action(contract, "activate", null, null);
        assertThat(activate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(activate.getBody().get("currentStatus").asText()).isEqualTo("PROPOSED");

        String staleTag = proposed.getHeaders().getETag();
        assertThat(action(contract, "approve", staleTag, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> stale = action(contract, "cancel", staleTag, Map.of("reason", "customer gave up"));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);

        ResponseEntity<JsonNode> cancelled = action(contract, "cancel", null, Map.of("reason", "customer gave up"));
        assertThat(cancelled.getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.getBody().get("statusReason").asText()).isEqualTo("customer gave up");
    }

    @Test
    void invalidCpfIsRejectedWithoutEchoingIt() {
        UUID agreement = createAgreement("35.00", 96, "0.015");
        String valid = TestCpfs.random();
        String invalid = valid.substring(0, 10) + (char) ('0' + (valid.charAt(10) - '0' + 1) % 10);

        ResponseEntity<JsonNode> response = post("/api/v1/customers", Map.of(
                "cpf", invalid, "fullName", "Test", "agreementId", agreement,
                "netMonthlySalary", new BigDecimal("1000.00")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errors").get(0).get("field").asText()).isEqualTo("cpf");
        assertThat(response.getBody().toString()).doesNotContain(invalid);
    }

    @Test
    void customerResponsesMaskTheCpfAndDuplicatesAre409() {
        UUID agreement = createAgreement("35.00", 96, "0.015");
        String cpf = TestCpfs.random();
        Map<String, Object> body = Map.of("cpf", cpf, "fullName", "Test", "agreementId", agreement,
                "netMonthlySalary", new BigDecimal("1000.00"));

        ResponseEntity<JsonNode> created = post("/api/v1/customers", body);
        ResponseEntity<JsonNode> duplicate = post("/api/v1/customers", body);

        assertThat(created.getBody().get("cpf").asText()).isEqualTo("***.***.***-" + cpf.substring(9));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody().toString()).doesNotContain(cpf);
    }

    @Test
    void listingPaginatesNewestFirst() {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "50000.00");
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMinutes(1));
            propose(id(simulate(customer, "1000.00", 12)), UUID.randomUUID().toString());
        }

        JsonNode page = get("/api/v1/contracts?customerId=" + customer + "&status=PROPOSED&size=2&page=0").getBody();

        assertThat(page.get("content")).hasSize(2);
        assertThat(page.get("totalElements").asInt()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page.get("content").get(0).get("createdAt").asText())
                .isGreaterThan(page.get("content").get(1).get("createdAt").asText());
    }

    @Test
    void unknownIdsAre404Problems() {
        ResponseEntity<JsonNode> response = get("/api/v1/contracts/" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
    }

    private static <T> List<T> runConcurrently(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
