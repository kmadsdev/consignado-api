package io.github.kmadsdev.consignado.api;

import io.github.kmadsdev.consignado.application.ContractService;
import io.github.kmadsdev.consignado.application.Views.ContractView;
import io.github.kmadsdev.consignado.application.Views.InstallmentView;
import io.github.kmadsdev.consignado.application.Views.PageView;
import io.github.kmadsdev.consignado.application.Views.ProposalResult;
import io.github.kmadsdev.consignado.domain.ContractStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/contracts")
@Tag(name = "Contracts", description = "Contract lifecycle: PROPOSED -> APPROVED/REJECTED -> ACTIVE -> SETTLED/CANCELLED")
class ContractController {

    /**
     * JPA takes the offset (page * size) as an int, so an unbounded page number ends in a 500.
     * 10,000 pages of 100 rows is far beyond what an offset listing should serve anyway.
     */
    private static final int MAX_PAGE = 10_000;
    private static final String IF_MATCH_DOC = "Optional. The ETag from the last read. A stale value returns 412.";

    private final ContractService service;

    ContractController(ContractService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Propose a contract from a simulation",
            description = "201 on first call. The same Idempotency-Key and body return 200 with the same contract.")
    ResponseEntity<ContractView> propose(
            @Parameter(description = "Client-generated key, 8 to 64 chars of [A-Za-z0-9_-]. A UUID works.")
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String idempotencyKey,
            @Valid @RequestBody Requests.CreateContract body) {
        ProposalResult result = service.propose(idempotencyKey, body.simulationId());
        ContractView view = result.contract();
        if (!result.created()) {
            return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
        }
        return ResponseEntity.created(
                        ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(view.id()).toUri())
                .eTag(ETags.of(view.version()))
                .body(view);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a contract", description = "The ETag header carries the version for If-Match")
    ResponseEntity<ContractView> get(@PathVariable UUID id) {
        return withETag(service.get(id));
    }

    @GetMapping
    @Operation(summary = "List contracts, newest first")
    PageView<ContractView> list(@RequestParam(required = false) UUID customerId,
                                @RequestParam(required = false) ContractStatus status,
                                @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(customerId, status, page, size);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve", description = "Checks the margin again with the customer row locked")
    ResponseEntity<ContractView> approve(@PathVariable UUID id,
                                         @Parameter(description = IF_MATCH_DOC)
                                         @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return withETag(service.approve(id, ETags.parseIfMatch(ifMatch)));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject")
    ResponseEntity<ContractView> reject(@PathVariable UUID id, @Valid @RequestBody Requests.Reason body,
                                        @Parameter(description = IF_MATCH_DOC)
                                        @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return withETag(service.reject(id, body.reason(), ETags.parseIfMatch(ifMatch)));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate", description = "Writes the installment schedule")
    ResponseEntity<ContractView> activate(@PathVariable UUID id,
                                          @Parameter(description = IF_MATCH_DOC)
                                          @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return withETag(service.activate(id, ETags.parseIfMatch(ifMatch)));
    }

    @PostMapping("/{id}/settle")
    @Operation(summary = "Settle")
    ResponseEntity<ContractView> settle(@PathVariable UUID id,
                                        @Parameter(description = IF_MATCH_DOC)
                                        @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return withETag(service.settle(id, ETags.parseIfMatch(ifMatch)));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel")
    ResponseEntity<ContractView> cancel(@PathVariable UUID id, @Valid @RequestBody Requests.Reason body,
                                        @Parameter(description = IF_MATCH_DOC)
                                        @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return withETag(service.cancel(id, body.reason(), ETags.parseIfMatch(ifMatch)));
    }

    @GetMapping("/{id}/installments")
    @Operation(summary = "Installment schedule", description = "Empty until the contract is ACTIVE")
    List<InstallmentView> installments(@PathVariable UUID id) {
        return service.installments(id);
    }

    private static ResponseEntity<ContractView> withETag(ContractView view) {
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }
}
