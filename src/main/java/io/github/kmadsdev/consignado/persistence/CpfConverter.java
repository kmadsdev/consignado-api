package io.github.kmadsdev.consignado.persistence;

import io.github.kmadsdev.consignado.domain.Cpf;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores the 11 digits; reading back goes through validation again. */
@Converter(autoApply = true)
public class CpfConverter implements AttributeConverter<Cpf, String> {

    @Override
    public String convertToDatabaseColumn(Cpf cpf) {
        return cpf == null ? null : cpf.value();
    }

    @Override
    public Cpf convertToEntityAttribute(String digits) {
        return digits == null ? null : Cpf.of(digits);
    }
}
