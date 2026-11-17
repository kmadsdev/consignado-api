package io.github.kmadsdev.consignado;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kmadsdev.consignado.testsupport.ApiTestBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The listing endpoints over HTTP against PostgreSQL. */
class ReadEndpointsIT extends ApiTestBase {

    /** Far beyond what any other test moves the clock, so these rows are the newest in the shared database. */
    private static final Duration FUTURE = Duration.ofDays(400);

    @Test
    void customersAreListedNewestFirstWithTheCpfMasked() {
        UUID agreement = createAgreement("35.00", 96, "0.015");
        clock.advance(FUTURE);
        UUID older = createCustomer(agreement, "3000.00");
        clock.advance(Duration.ofMinutes(1));
        UUID newer = createCustomer(agreement, "4000.00");

        ResponseEntity<JsonNode> response = get("/api/v1/customers?size=2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(ids(body.get("content"))).containsExactly(newer, older);
        assertThat(body.get("content").get(0).get("cpf").asText()).startsWith("***.");
        assertThat(body.get("page").asInt()).isZero();
        assertThat(body.get("size").asInt()).isEqualTo(2);
        assertThat(body.get("totalElements").asLong()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void theSecondPageOfCustomersContinuesWhereTheFirstStopped() {
        UUID agreement = createAgreement("35.00", 96, "0.015");
        clock.advance(FUTURE.plusDays(1));
        UUID older = createCustomer(agreement, "3000.00");
        clock.advance(Duration.ofMinutes(1));
        createCustomer(agreement, "4000.00");

        JsonNode secondPage = get("/api/v1/customers?page=1&size=1").getBody();

        assertThat(ids(secondPage.get("content"))).containsExactly(older);
    }

    @Test
    void agreementsAreListedNewestFirst() {
        clock.advance(FUTURE);
        UUID older = createAgreement("35.00", 96, "0.015");
        clock.advance(Duration.ofMinutes(1));
        UUID newer = createAgreement("30.00", 84, "0.018");

        ResponseEntity<JsonNode> response = get("/api/v1/agreements");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().isArray()).isTrue();
        assertThat(ids(response.getBody())).startsWith(newer, older);
    }

    @Test
    void simulationsAreListedNewestFirstWithoutTheSchedule() {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "5000.00");
        clock.advance(FUTURE);
        UUID older = id(simulate(customer, "1000.00", 12));
        clock.advance(Duration.ofMinutes(1));
        UUID newer = id(simulate(customer, "2000.00", 12));

        ResponseEntity<JsonNode> response = get("/api/v1/simulations?size=2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = response.getBody().get("content");
        assertThat(ids(content)).containsExactly(newer, older);
        assertThat(decimal(content.get(0).get("principal"))).isEqualByComparingTo("2000.00");
        assertThat(content.get(0).has("schedule")).isFalse();
        assertThat(get("/api/v1/simulations/" + newer).getBody().get("schedule")).hasSize(12);
    }

    @Test
    void aPageNumberBeyondTheOffsetRangeIs400OnEveryPagedListing() {
        for (String path : List.of("/api/v1/customers", "/api/v1/simulations")) {
            ResponseEntity<JsonNode> response = get(path + "?page=2147483647&size=100");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code").asText()).isEqualTo("validation-failed");
        }
    }

    private static List<UUID> ids(JsonNode array) {
        List<UUID> ids = new ArrayList<>();
        array.forEach(node -> ids.add(UUID.fromString(node.get("id").asText())));
        return ids;
    }
}
