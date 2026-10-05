package com.ktogroup.ktoggle.commons.exception;

import java.util.List;
import org.springframework.http.HttpStatus;

public class ValidationException extends KtoggleException {

    public ValidationException(MessageCode messageCode, String message) {
        super(messageCode, message);
    }

    public ValidationException(MessageCode messageCode, String message, List<String> errors) {
        super(messageCode, message, errors);
    }

    public static ValidationException of(String message) {
        return new ValidationException(MessageCode.VALIDATION_ERROR, message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }
}
