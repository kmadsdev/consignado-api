package io.github.kmadsdev.consignado.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.kmadsdev.consignado.domain.PriceAmortization;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Request bodies of /api/v1. Validation happens here, before any use case runs. */
final class Requests {

    private Requests() {
    }

    record CreateAgreement(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Z0-9_-]+", message = "use A-Z, 0-9, '_' or '-'")
            String code,
            @NotBlank @Size(max = 120)
            String name,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") @Digits(integer = 3, fraction = 2)
            BigDecimal maxMarginPercent,
            @NotNull @Min(1) @Max(420)
            Integer maxTermMonths,
            @NotNull @DecimalMin("0") @DecimalMax(value = "1", inclusive = false) @Digits(integer = 1, fraction = 6)
            BigDecimal monthlyInterestRate) {
    }

    record CreateCustomer(
            @NotBlank @ValidCpf
            String cpf,
            @NotBlank @Size(max = 160)
            String fullName,
            @NotNull
            UUID agreementId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 2)
            BigDecimal netMonthlySalary) {
    }

    /** Same rules as {@code netMonthlySalary} in {@link CreateCustomer}. */
    record ChangeIncome(
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 2)
            BigDecimal monthlyIncome) {
    }

    record CreateSimulation(
            @NotNull
            UUID customerId,
            @NotNull @DecimalMin("100.00") @DecimalMax("1000000.00") @Digits(integer = 13, fraction = 2)
            BigDecimal amount,
            @NotNull @Min(1) @Max(420)
            Integer termMonths) {
    }

    /**
     * The guest calculation. The bounds are those of a stored simulation (amount) and of the
     * domain (term, rate), so the largest schedule a guest can ask for is 420 lines.
     */
    record PreviewSimulation(
            @NotNull @DecimalMin("100.00") @DecimalMax("1000000.00") @Digits(integer = 13, fraction = 2)
            BigDecimal principal,
            @NotNull @Min(1) @Max(PriceAmortization.MAX_TERM_MONTHS)
            Integer termMonths,
            @NotNull @DecimalMin("0") @DecimalMax(value = "1", inclusive = false) @Digits(integer = 1, fraction = 6)
            BigDecimal monthlyInterestRate,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 2)
            BigDecimal monthlyIncome,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") @Digits(integer = 3, fraction = 2)
            BigDecimal maxMarginPercent) {

        /** The margin check needs both numbers. One without the other is a mistake, not a default. */
        @JsonIgnore
        @AssertTrue(message = "monthlyIncome and maxMarginPercent must be sent together")
        boolean isMarginInputComplete() {
            return (monthlyIncome == null) == (maxMarginPercent == null);
        }
    }

    record CreateContract(@NotNull UUID simulationId) {
    }

    record Reason(@NotBlank @Size(max = 255) String reason) {
    }
}
