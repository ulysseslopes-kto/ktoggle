package com.ktogroup.ktoggle.commons.exception;

import org.springframework.http.HttpStatus;

/** Raised when stored, supposedly immutable data fails hash or signature verification. */
public class IntegrityException extends KtoggleException {

    public IntegrityException(String message) {
        super(MessageCode.BUNDLE_INTEGRITY_VIOLATION, message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
