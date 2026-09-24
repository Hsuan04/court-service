package com.courtservice.common.error;

/**
 * Thrown when a resource looked up by identifier does not exist.
 */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String resourceName, Object id) {
        super(ErrorCode.RESOURCE_NOT_FOUND, "%s with id %s was not found.".formatted(resourceName, id));
    }
}