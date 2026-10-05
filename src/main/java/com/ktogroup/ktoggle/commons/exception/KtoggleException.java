package com.ktogroup.ktoggle.commons.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base of every business exception. Carries a stable {@link MessageCode} so clients (the admin UI)
 * can react to the error without parsing messages.
 */
@Getter
public abstract class KtoggleException extends RuntimeException {

    private final MessageCode messageCode;
    private final transient Object data;

    protected KtoggleException(MessageCode messageCode, String message) {
        this(messageCode, message, null);
    }

    protected KtoggleException(MessageCode messageCode, String message, Object data) {
        super(message);
        this.messageCode = messageCode;
        this.data = data;
    }

    public abstract HttpStatus status();
}
