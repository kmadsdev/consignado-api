package io.github.kmadsdev.consignado.domain;

/**
 * Base type for rule violations raised by the domain. The {@code code} is a stable,
 * machine-readable identifier that the API exposes in its problem responses.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
