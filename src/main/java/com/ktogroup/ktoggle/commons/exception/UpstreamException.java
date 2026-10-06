package com.ktogroup.ktoggle.commons.exception;

import org.springframework.http.HttpStatus;

/** An external system ktoggle reads from (e.g. GrowthBook during a migration) failed or answered unexpectedly. */
public class UpstreamException extends KtoggleException {

    public UpstreamException(String message) {
        super(MessageCode.UPSTREAM_ERROR, message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_GATEWAY;
    }
}
