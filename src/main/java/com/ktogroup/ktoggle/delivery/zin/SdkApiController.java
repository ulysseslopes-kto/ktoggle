package com.ktogroup.ktoggle.delivery.zin;

import com.ktogroup.ktoggle.commons.exception.MessageCode;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.delivery.ActiveBundleRegistry;
import com.ktogroup.ktoggle.delivery.DeliveryChannel;
import com.ktogroup.ktoggle.delivery.DeliveryRecorder;
import com.ktogroup.ktoggle.delivery.ServedPayload;
import com.ktogroup.ktoggle.delivery.SseHub;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * GrowthBook-compatible SDK endpoints: existing consumers (growthbook-sdk-java, @growthbook/growthbook,
 * growthbook-react) migrate by changing only the API host.
 */
@Tag(name = "SDK (GrowthBook compatible)")
@RestController
@RequiredArgsConstructor
public class SdkApiController {

    static final String BUNDLE_HEADER = "X-Ktoggle-Bundle";
    /** GrowthBook JS SDKs only open the SSE stream when the payload response advertises it (as the GB Proxy does). */
    static final String SSE_SUPPORT_HEADER = "x-sse-support";

    private final ActiveBundleRegistry registry;
    private final DeliveryRecorder deliveryRecorder;
    private final SseHub sseHub;

    @Operation(summary = "Feature payload for a client key (ETag = bundle hash, 304 when unchanged)")
    @GetMapping(value = "/api/features/{clientKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> features(@PathVariable String clientKey,
                                           @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                                           @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        ServedPayload payload = served(clientKey);
        deliveryRecorder.record(clientKey, payload.bundleHash(), DeliveryChannel.POLL, userAgent);
        HttpStatus status = payload.etag().equals(ifNoneMatch) ? HttpStatus.NOT_MODIFIED : HttpStatus.OK;
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                .eTag(payload.etag())
                .header(BUNDLE_HEADER, payload.bundleHash())
                .header(SSE_SUPPORT_HEADER, "enabled")
                .cacheControl(CacheControl.noCache());
        return status == HttpStatus.NOT_MODIFIED ? response.build() : response.body(payload.body());
    }

    @Operation(summary = "Server-sent events stream: a 'features' event on connect and on every change")
    @GetMapping(value = "/sub/{clientKey}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable String clientKey,
                                @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return sseHub.subscribe(served(clientKey), userAgent);
    }

    private ServedPayload served(String clientKey) {
        return registry.get(clientKey).orElseThrow(() ->
                new NotFoundException(MessageCode.UNKNOWN_CLIENT_KEY, "Unknown client key"));
    }
}
