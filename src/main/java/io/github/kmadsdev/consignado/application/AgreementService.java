package io.github.kmadsdev.consignado.application;

import io.github.kmadsdev.consignado.application.Views.AgreementView;
import io.github.kmadsdev.consignado.persistence.Agreement;
import io.github.kmadsdev.consignado.persistence.AgreementRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AgreementService {

    private final AgreementRepository agreements;
    private final Clock clock;

    public AgreementService(AgreementRepository agreements, Clock clock) {
        this.agreements = agreements;
        this.clock = clock;
    }

    public record NewAgreement(String code, String name, BigDecimal maxMarginPercent, int maxTermMonths,
                               BigDecimal monthlyInterestRate) {
    }

    @Transactional
    public AgreementView create(NewAgreement command) {
        if (agreements.existsByCode(command.code())) {
            throw new ConflictException("agreement-code-taken", "An agreement with this code already exists");
        }
        Agreement agreement = agreements.save(new Agreement(command.code(), command.name(),
                command.maxMarginPercent(), command.maxTermMonths(), command.monthlyInterestRate(), clock.instant()));
        return AgreementView.from(agreement);
    }

    public AgreementView get(UUID id) {
        return agreements.findById(id)
                .map(AgreementView::from)
                .orElseThrow(() -> new ResourceNotFoundException("Agreement", id));
    }
}
