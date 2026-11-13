package io.github.kmadsdev.consignado.application;

/** The request conflicts with the current state of a resource (duplicate, already used). */
public class ConflictException extends ApplicationException {

    public ConflictException(String code, String message) {
        super(code, message);
    }
}
