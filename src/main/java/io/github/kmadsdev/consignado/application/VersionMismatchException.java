package io.github.kmadsdev.consignado.application;

/** The client's If-Match version is not the current version of the resource. */
public class VersionMismatchException extends ApplicationException {

    public VersionMismatchException(Long expected, Long actual) {
        super("version-mismatch", "Expected version " + expected + " but the current version is " + actual);
    }
}
