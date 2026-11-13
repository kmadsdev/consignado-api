package io.github.kmadsdev.consignado.application;

import java.util.UUID;

public class ResourceNotFoundException extends ApplicationException {

    public ResourceNotFoundException(String resource, UUID id) {
        super("not-found", resource + " " + id + " does not exist");
    }
}
