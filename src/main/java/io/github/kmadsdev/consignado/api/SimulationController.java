package io.github.kmadsdev.consignado.api;

import io.github.kmadsdev.consignado.application.SimulationService;
import io.github.kmadsdev.consignado.application.Views.SimulationView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/simulations")
@Tag(name = "Simulations", description = "Loan quotes with the Price schedule and the margin check")
class SimulationController {

    private final SimulationService service;

    SimulationController(SimulationService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Simulate a loan", description = "Fails with 422 margin-exceeded when the installment does not fit")
    ResponseEntity<SimulationView> simulate(@Valid @RequestBody Requests.CreateSimulation body) {
        SimulationView view = service.simulate(body.customerId(), body.amount(), body.termMonths());
        return ResponseEntity.created(
                ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(view.id()).toUri())
                .body(view);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a simulation with its schedule")
    SimulationView get(@PathVariable UUID id) {
        return service.get(id);
    }
}
