package io.github.kmadsdev.consignado.application;

/** The same Idempotency-Key was sent again with a different request body. */
public class IdempotencyKeyReuseException extends ApplicationException {

    public IdempotencyKeyReuseException() {
        super("idempotency-key-reused", "This Idempotency-Key was already used for a different simulation");
    }
}
