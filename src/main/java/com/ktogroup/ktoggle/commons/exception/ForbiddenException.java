package com.ktogroup.ktoggle.commons.exception;

import org.springframework.http.HttpStatus;

/** The user is authenticated but a business policy (e.g. review rules) does not allow the action. */
public class ForbiddenException extends KtoggleException {

    public ForbiddenException(MessageCode messageCode, String message) {
        super(messageCode, message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.FORBIDDEN;
    }
}
