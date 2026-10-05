package com.ktogroup.ktoggle.webhook;

import java.util.Map;

/** Sends one HTTP POST. Implemented in {@code zout}; any exception counts as a failed attempt. */
public interface WebhookTransport {

    /** @return the HTTP status code */
    int post(String url, Map<String, String> headers, String body) throws Exception;
}
