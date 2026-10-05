package com.ktogroup.ktoggle.commons.exception.zdto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ktogroup.ktoggle.commons.exception.MessageCode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String message, MessageCode messageCode, Object data) {

    public static ErrorResponse of(String message, MessageCode code) {
        return new ErrorResponse(message, code, null);
    }
}
