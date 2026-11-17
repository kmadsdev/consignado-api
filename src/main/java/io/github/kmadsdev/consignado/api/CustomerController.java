package io.github.kmadsdev.consignado.api;

import io.github.kmadsdev.consignado.application.CustomerService;
import io.github.kmadsdev.consignado.application.CustomerService.NewCustomer;
import io.github.kmadsdev.consignado.application.Views.CustomerView;
import io.github.kmadsdev.consignado.application.Views.MarginView;
import io.github.kmadsdev.consignado.application.Views.PageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * There is no "find by CPF" query parameter on purpose: URLs end up in access logs and
 * proxies. A lookup by CPF would be a POST with the CPF in the body.
 */
@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers", description = "Borrowers. CPF is validated and always returned masked")
class CustomerController {

    private final CustomerService service;

    CustomerController(CustomerService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Register a customer under an agreement")
    ResponseEntity<CustomerView> create(@Valid @RequestBody Requests.CreateCustomer body) {
        CustomerView view = service.create(
                new NewCustomer(body.cpf(), body.fullName(), body.agreementId(), body.netMonthlySalary()));
        return ResponseEntity.created(
                ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(view.id()).toUri())
                .body(view);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a customer")
    CustomerView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    @Operation(summary = "List customers, newest first", description = "The CPF is masked in every row")
    PageView<CustomerView> list(@RequestParam(defaultValue = "0") @Min(0) @Max(Paging.MAX_PAGE) int page,
                                @RequestParam(defaultValue = Paging.DEFAULT_SIZE) @Min(1) @Max(Paging.MAX_SIZE)
                                int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}/margin")
    @Operation(summary = "Consignable margin available now",
            description = "Cap from the agreement minus installments of APPROVED and ACTIVE contracts")
    MarginView margin(@PathVariable UUID id) {
        return service.margin(id);
    }
}
