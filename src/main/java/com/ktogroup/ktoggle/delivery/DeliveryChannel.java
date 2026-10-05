package com.ktogroup.ktoggle.delivery;

public enum DeliveryChannel {
    POLL,
    SSE,
    /** POST /api/eval: values evaluated server-side for the posted attributes. */
    REMOTE_EVAL
}
