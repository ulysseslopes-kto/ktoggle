package com.ktogroup.ktoggle.commons.exception;

import org.springframework.http.HttpStatus;

public class ConflictException extends KtoggleException {

    public ConflictException(MessageCode messageCode, String message) {
        super(messageCode, message);
    }

    public static ConflictException alreadyExists(String entity, String key) {
        return new ConflictException(MessageCode.ENTITY_ALREADY_EXISTS, "%s '%s' already exists".formatted(entity, key));
    }

    public static ConflictException staleVersion(String entity, String key, long expected, long actual) {
        return new ConflictException(MessageCode.CONCURRENT_MODIFICATION,
                "%s '%s' was modified concurrently (expected version %d, current %d)".formatted(entity, key, expected, actual));
    }

    public static ConflictException inUse(String entity, String key, String usedBy) {
        return new ConflictException(MessageCode.ENTITY_IN_USE, "%s '%s' is in use by %s".formatted(entity, key, usedBy));
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
