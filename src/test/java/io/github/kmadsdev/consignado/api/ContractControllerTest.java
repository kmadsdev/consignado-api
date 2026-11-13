package io.github.kmadsdev.consignado.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kmadsdev.consignado.application.ContractService;
import io.github.kmadsdev.consignado.application.ResourceNotFoundException;
import io.github.kmadsdev.consignado.application.VersionMismatchException;
import io.github.kmadsdev.consignado.application.Views.ContractView;
import io.github.kmadsdev.consignado.application.Views.PageView;
import io.github.kmadsdev.consignado.application.Views.ProposalResult;
import io.github.kmadsdev.consignado.domain.ContractStatus;
import io.github.kmadsdev.consignado.domain.IllegalStatusTransitionException;
import io.github.kmadsdev.consignado.domain.MarginExceededException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web slice: HTTP contract of the controller with the use case mocked. */
@WebMvcTest(ContractController.class)
class ContractControllerTest {

    private static final String KEY = "c0ffee00-key-0001";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContractService service;

    @Test
    void firstProposalReturns201WithLocationAndETag() throws Exception {
        ContractView view = contract(ContractStatus.PROPOSED, 0);
        given(service.propose(KEY, view.simulationId())).willReturn(new ProposalResult(view, true));

        mvc.perform(post("/api/v1/contracts").header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simulationId\":\"" + view.simulationId() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/contracts/" + view.id())))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.status").value("PROPOSED"));
    }

    @Test
    void replayedProposalReturns200() throws Exception {
        ContractView view = contract(ContractStatus.PROPOSED, 0);
        given(service.propose(KEY, view.simulationId())).willReturn(new ProposalResult(view, false));

        mvc.perform(post("/api/v1/contracts").header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simulationId\":\"" + view.simulationId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(view.id().toString()));
    }

    @Test
    void missingIdempotencyKeyIsA400Problem() throws Exception {
        mvc.perform(post("/api/v1/contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simulationId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail", containsString("Idempotency-Key")));
        verifyNoInteractions(service);
    }

    @Test
    void malformedIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/api/v1/contracts").header("Idempotency-Key", "short")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simulationId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("idempotencyKey")));
        verifyNoInteractions(service);
    }

    @Test
    void invalidBodyListsTheFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/contracts").header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:consignado:problem:validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("simulationId"));
    }

    @Test
    void illegalTransitionIs409WithBothStatuses() throws Exception {
        UUID id = UUID.randomUUID();
        given(service.activate(id, null))
                .willThrow(new IllegalStatusTransitionException(ContractStatus.PROPOSED, ContractStatus.ACTIVE));

        mvc.perform(post("/api/v1/contracts/{id}/activate", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("illegal-status-transition"))
                .andExpect(jsonPath("$.currentStatus").value("PROPOSED"))
                .andExpect(jsonPath("$.requestedStatus").value("ACTIVE"));
    }

    @Test
    void marginExceededIs422WithTheNumbers() throws Exception {
        UUID id = UUID.randomUUID();
        given(service.approve(id, null))
                .willThrow(new MarginExceededException(new BigDecimal("350.00"), new BigDecimal("916.80")));

        mvc.perform(post("/api/v1/contracts/{id}/approve", id))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("margin-exceeded"))
                .andExpect(jsonPath("$.availableMargin").value(350.00))
                .andExpect(jsonPath("$.installment").value(916.80));
    }

    @Test
    void ifMatchIsPassedAsTheExpectedVersionAndAStaleOneIs412() throws Exception {
        UUID id = UUID.randomUUID();
        given(service.approve(id, 3L)).willThrow(new VersionMismatchException(3L, 4L));

        mvc.perform(post("/api/v1/contracts/{id}/approve", id).header("If-Match", "\"3\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("version-mismatch"));
    }

    @Test
    void rejectNeedsAReason() throws Exception {
        mvc.perform(post("/api/v1/contracts/{id}/reject", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
    }

    @Test
    void unknownContractIs404() throws Exception {
        UUID id = UUID.randomUUID();
        given(service.get(id)).willThrow(new ResourceNotFoundException("Contract", id));

        mvc.perform(get("/api/v1/contracts/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void pageSizeIsCappedAt100() throws Exception {
        mvc.perform(get("/api/v1/contracts").param("size", "101"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void unknownStatusFilterIs400() throws Exception {
        mvc.perform(get("/api/v1/contracts").param("status", "PAID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listReturnsThePageEnvelope() throws Exception {
        given(service.list(isNull(), eq(ContractStatus.ACTIVE), eq(0), anyInt()))
                .willReturn(new PageView<>(List.of(contract(ContractStatus.ACTIVE, 2)), 0, 20, 1, 1));

        mvc.perform(get("/api/v1/contracts").param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() throws Exception {
        UUID id = UUID.randomUUID();
        given(service.get(any())).willThrow(new IllegalStateException("SELECT secret FROM somewhere"));

        mvc.perform(get("/api/v1/contracts/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal-error"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("SELECT"))));
    }

    private static ContractView contract(ContractStatus status, long version) {
        Instant now = Instant.parse("2026-03-01T12:00:00Z");
        return new ContractView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), status,
                new BigDecimal("10000.00"), new BigDecimal("0.015000"), 12, new BigDecimal("916.80"),
                null, version, now, now);
    }
}
