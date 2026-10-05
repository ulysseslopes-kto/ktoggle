package com.ktogroup.ktoggle.commons.exception;

import org.springframework.http.HttpStatus;

public class NotFoundException extends KtoggleException {

    public NotFoundException(String entity, String key) {
        super(MessageCode.ENTITY_NOT_FOUND, "%s '%s' not found".formatted(entity, key));
    }

    public NotFoundException(MessageCode code, String message) {
        super(code, message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
