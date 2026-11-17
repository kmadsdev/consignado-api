package io.github.kmadsdev.consignado.api;

import io.github.kmadsdev.consignado.application.AgreementService;
import io.github.kmadsdev.consignado.application.AgreementService.NewAgreement;
import io.github.kmadsdev.consignado.application.Views.AgreementView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/agreements")
@Tag(name = "Agreements", description = "Employer agreements (convenios) and their limits")
class AgreementController {

    private final AgreementService service;

    AgreementController(AgreementService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Register an employer agreement")
    ResponseEntity<AgreementView> create(@Valid @RequestBody Requests.CreateAgreement body) {
        AgreementView view = service.create(new NewAgreement(body.code(), body.name(), body.maxMarginPercent(),
                body.maxTermMonths(), body.monthlyInterestRate()));
        return ResponseEntity.created(
                ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(view.id()).toUri())
                .body(view);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an agreement")
    AgreementView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    @Operation(summary = "List agreements, newest first", description = "Not paged: agreements are reference data")
    List<AgreementView> list() {
        return service.list();
    }
}
