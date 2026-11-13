package io.github.kmadsdev.consignado.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kmadsdev.consignado.testsupport.ApiTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ObservabilityIT extends ApiTestBase {

    @Test
    void readinessAndLivenessProbesAreUp() {
        ResponseEntity<JsonNode> readiness = get("/actuator/health/readiness");
        ResponseEntity<JsonNode> liveness = get("/actuator/health/liveness");

        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody().get("status").asText()).isEqualTo("UP");
        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void prometheusEndpointExposesHttpAndBusinessMetrics() {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "5000.00");
        propose(id(simulate(customer, "1000.00", 12)), UUID.randomUUID().toString());

        String scrape = rest.getForObject("/actuator/prometheus", String.class);

        assertThat(scrape)
                .contains("http_server_requests_seconds_count")
                .contains("consignado_simulations_total{application=\"consignado-api\",outcome=\"accepted\"}")
                .contains("consignado_contract_transitions_total")
                .contains("to=\"PROPOSED\"")
                .contains("consignado_outbox_pending");
    }

    @Test
    void echoesACallerCorrelationIdAndPutsItInProblemResponses() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationIdFilter.HEADER, "client-req-42");

        ResponseEntity<JsonNode> response = rest.exchange("/api/v1/contracts/" + UUID.randomUUID(), HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo("client-req-42");
        assertThat(response.getBody().get("correlationId").asText()).isEqualTo("client-req-42");
    }

    @Test
    void generatesACorrelationIdWhenTheCallerSendsNone() {
        ResponseEntity<JsonNode> response = get("/api/v1/contracts/not-a-uuid");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String generated = response.getHeaders().getFirst(CorrelationIdFilter.HEADER);
        assertThat(UUID.fromString(generated)).isNotNull();
        assertThat(response.getBody().get("correlationId").asText()).isEqualTo(generated);
    }

    @Test
    void openApiDocumentDescribesTheVersionedApi() {
        JsonNode doc = get("/v3/api-docs").getBody();

        assertThat(doc.get("info").get("title").asText()).isEqualTo("Consignado API");
        assertThat(doc.get("paths").has("/api/v1/contracts")).isTrue();
        assertThat(doc.get("paths").has("/api/v1/contracts/{id}/approve")).isTrue();
    }
}
