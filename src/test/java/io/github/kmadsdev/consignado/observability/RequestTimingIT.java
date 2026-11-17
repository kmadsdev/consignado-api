package io.github.kmadsdev.consignado.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kmadsdev.consignado.testsupport.ApiTestBase;
import io.github.kmadsdev.consignado.testsupport.TestCpfs;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The timing headers against the real application and a real PostgreSQL. */
@ExtendWith(OutputCaptureExtension.class)
class RequestTimingIT extends ApiTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NO_DB = "app;dur=\\d+\\.\\d";
    private static final String WITH_DB = "app;dur=\\d+\\.\\d, db;dur=\\d+\\.\\d;desc=\"\\d+ statements?\"";

    @Test
    void theLoanPreviewRunsNoStatement() throws Exception {
        ResponseEntity<JsonNode> response = post("/api/v1/simulations/preview", Map.of(
                "principal", new BigDecimal("10000.00"), "termMonths", 12,
                "monthlyInterestRate", new BigDecimal("0.015")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(RequestTimingFilter.SERVER_TIMING)).matches(NO_DB);
        JsonNode timing = timing(response);
        assertThat(timing.get("db").isNull()).isTrue();
        assertThat(timing.get("instance").asText()).isNotBlank();
        assertThat(timing.get("appMs").asDouble()).isGreaterThan(0.0);
        example("preview", response);
    }

    @Test
    void creatingACustomerReportsTheInsertAndItsTransaction(CapturedOutput output) throws Exception {
        UUID agreement = createAgreement("35.00", 96, "0.015");
        String cpf = TestCpfs.formatted(TestCpfs.random());

        ResponseEntity<JsonNode> response = post("/api/v1/customers", Map.of(
                "cpf", cpf, "fullName", "Timing Customer", "agreementId", agreement,
                "netMonthlySalary", new BigDecimal("5000.00")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst(RequestTimingFilter.SERVER_TIMING)).matches(WITH_DB);
        JsonNode db = timing(response).get("db");
        assertThat(db.get("insert").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(db.get("ms").asDouble()).isGreaterThan(0.0);
        assertThat(db.get("statements").asInt()).isEqualTo(db.get("select").asInt() + db.get("insert").asInt()
                + db.get("update").asInt() + db.get("delete").asInt() + db.get("other").asInt());
        assertThat(db.get("transactions").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(db.get("connectionMs").asDouble()).isGreaterThanOrEqualTo(0.0);
        assertThat(timing(response).get("appMs").asDouble()).isGreaterThanOrEqualTo(db.get("ms").asDouble());
        example("create customer", response);

        String digits = cpf.replaceAll("\\D", "");
        String headers = response.getHeaders().toString();
        assertThat(headers).doesNotContain("Timing Customer").doesNotContain(digits).doesNotContainIgnoringCase(
                "insert into");
        assertThat(output.getOut()).contains("POST /api/v1/customers status=201").contains(" insert=")
                .doesNotContainIgnoringCase("insert into").doesNotContain(digits);
    }

    @Test
    void listingCustomersReportsSelectsAndNoInsert() throws Exception {
        createCustomer(createAgreement("35.00", 96, "0.015"), "5000.00");

        ResponseEntity<JsonNode> response = get("/api/v1/customers?size=5");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode db = timing(response).get("db");
        assertThat(db.get("select").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(db.get("insert").asInt()).isZero();
        assertThat(db.get("update").asInt()).isZero();
        assertThat(db.get("delete").asInt()).isZero();
        example("list customers", response);
    }

    @Test
    void errorResponsesCarryTheHeadersToo() throws Exception {
        ResponseEntity<JsonNode> notFound = get("/api/v1/customers/" + UUID.randomUUID());
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getHeaders().getFirst(RequestTimingFilter.SERVER_TIMING)).matches(WITH_DB);
        assertThat(timing(notFound).get("db").get("select").asInt()).isGreaterThanOrEqualTo(1);

        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "5000.00");
        ResponseEntity<JsonNode> marginExceeded = simulate(customer, "100000.00", 12);
        assertThat(marginExceeded.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(timing(marginExceeded).get("db").get("statements").asInt()).isGreaterThanOrEqualTo(1);
        example("422", marginExceeded);

        ResponseEntity<JsonNode> invalid = post("/api/v1/customers", Map.of("fullName", "x"));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getHeaders().getFirst(RequestTimingFilter.SERVER_TIMING)).matches(NO_DB);
        assertThat(timing(invalid).get("db").isNull()).isTrue();
    }

    @Test
    void theRowLockedIncomeChangeIsCountedInsideItsTransaction() throws Exception {
        UUID customer = createCustomer(createAgreement("35.00", 96, "0.015"), "5000.00");

        ResponseEntity<JsonNode> response = changeIncome(customer, "6000.00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode db = timing(response).get("db");
        assertThat(db.get("select").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(db.get("update").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(db.get("transactions").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void metricsAndPoolMetricsAreExposed() {
        post("/api/v1/simulations/preview", Map.of("principal", new BigDecimal("10000.00"), "termMonths", 12,
                "monthlyInterestRate", new BigDecimal("0.015")));
        createCustomer(createAgreement("35.00", 96, "0.015"), "5000.00");

        String scrape = rest.getForObject("/actuator/prometheus", String.class);

        assertThat(scrape)
                .contains("consignado_db_request_statements_count")
                .contains("consignado_db_request_time_seconds_count")
                .contains("hikaricp_connections_active");
    }

    private static JsonNode timing(ResponseEntity<?> response) throws Exception {
        String header = response.getHeaders().getFirst(RequestTimingFilter.HEADER);
        assertThat(header).isNotNull().doesNotContain("\n");
        return JSON.readTree(header);
    }

    /** Prints real header values into the build log, for the documentation. */
    private static void example(String name, ResponseEntity<?> response) {
        System.out.println("TIMING-EXAMPLE " + name + " | Server-Timing: "
                + response.getHeaders().getFirst(RequestTimingFilter.SERVER_TIMING) + " | X-Consignado-Timing: "
                + response.getHeaders().getFirst(RequestTimingFilter.HEADER));
    }
}
