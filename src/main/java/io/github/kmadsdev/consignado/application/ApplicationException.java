package io.github.kmadsdev.consignado.application;

/** Use-case level failure (not found, conflict, precondition) with a stable code. */
public abstract class ApplicationException extends RuntimeException {

    private final String code;

    protected ApplicationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
