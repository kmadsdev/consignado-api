package io.github.kmadsdev.consignado.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Full application on a random port, real PostgreSQL, real HTTP. Subclasses share one
 * Spring context (and one database container) through context caching.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, ApiTestBase.FixedClockConfig.class})
public abstract class ApiTestBase {

    public static final Instant START = Instant.parse("2026-03-02T13:00:00Z");

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected MutableClock clock;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(START);
        }
    }

    @BeforeEach
    void resetClock() {
        clock.set(START);
    }

    protected UUID createAgreement(String marginPercent, int maxTerm, String monthlyRate) {
        ResponseEntity<JsonNode> response = post("/api/v1/agreements", Map.of(
                "code", "AG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "name", "Test employer agreement",
                "maxMarginPercent", new BigDecimal(marginPercent),
                "maxTermMonths", maxTerm,
                "monthlyInterestRate", new BigDecimal(monthlyRate)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return id(response);
    }

    protected UUID createCustomer(UUID agreementId, String netSalary) {
        ResponseEntity<JsonNode> response = post("/api/v1/customers", Map.of(
                "cpf", TestCpfs.formatted(TestCpfs.random()),
                "fullName", "Test Customer",
                "agreementId", agreementId,
                "netMonthlySalary", new BigDecimal(netSalary)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return id(response);
    }

    protected ResponseEntity<JsonNode> simulate(UUID customerId, String amount, int term) {
        return post("/api/v1/simulations", Map.of(
                "customerId", customerId, "amount", new BigDecimal(amount), "termMonths", term));
    }

    protected ResponseEntity<JsonNode> propose(UUID simulationId, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        return exchange(HttpMethod.POST, "/api/v1/contracts", Map.of("simulationId", simulationId), headers);
    }

    protected ResponseEntity<JsonNode> action(UUID contractId, String action, String ifMatch, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (ifMatch != null) {
            headers.setIfMatch(ifMatch);
        }
        return exchange(HttpMethod.POST, "/api/v1/contracts/" + contractId + "/" + action, body, headers);
    }

    protected ResponseEntity<JsonNode> post(String path, Object body) {
        return exchange(HttpMethod.POST, path, body, new HttpHeaders());
    }

    protected ResponseEntity<JsonNode> get(String path) {
        return rest.getForEntity(path, JsonNode.class);
    }

    protected ResponseEntity<JsonNode> exchange(HttpMethod method, String path, Object body, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    protected static UUID id(ResponseEntity<JsonNode> response) {
        return UUID.fromString(response.getBody().get("id").asText());
    }

    protected static BigDecimal decimal(JsonNode node) {
        return new BigDecimal(node.asText());
    }
}
