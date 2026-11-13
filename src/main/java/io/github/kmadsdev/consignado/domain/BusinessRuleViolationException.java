package io.github.kmadsdev.consignado.domain;

/** The request is well formed, but a business rule does not allow it. */
public class BusinessRuleViolationException extends DomainException {

    public BusinessRuleViolationException(String code, String message) {
        super(code, message);
    }
}
