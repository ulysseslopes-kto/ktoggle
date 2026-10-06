package com.ktogroup.ktoggle.delivery.zin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.commons.exception.MessageCode;
import com.ktogroup.ktoggle.commons.exception.NotFoundException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.delivery.ActiveBundleRegistry;
import com.ktogroup.ktoggle.delivery.DeliveryChannel;
import com.ktogroup.ktoggle.delivery.DeliveryRecorder;
import com.ktogroup.ktoggle.delivery.ServedPayload;
import com.ktogroup.ktoggle.delivery.SseHub;
import com.ktogroup.ktoggle.delivery.RemoteEvaluator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final RemoteEvaluator remoteEvaluator;
    private final ObjectMapper objectMapper;

    @Operation(summary = "Feature payload for a client key (ETag = bundle hash and delivery mode, 304 when unchanged)")
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

    @Operation(summary = "Remote evaluation: values for the posted attributes, evaluated server-side; rules are never sent")
    @PostMapping(value = "/api/eval/{clientKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> evaluate(@PathVariable String clientKey, @RequestBody(required = false) RemoteEvalRequest request,
                                              @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        ServedPayload payload = served(clientKey);
        if (!payload.remoteEval()) {
            throw ValidationException.of("Remote evaluation is not enabled for this SDK connection");
        }
        RemoteEvalRequest body = request == null ? new RemoteEvalRequest(null, null, null, null) : request;
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", 200);
        response.set("features", remoteEvaluator.evaluate(payload.features(), body.attributes(), body.forcedVariations(),
                body.forcedFeatureMap(), body.url()));
        response.putArray("experiments");
        response.put("dateUpdated", payload.activatedAt().toString());
        response.put("bundleHash", payload.bundleHash());
        deliveryRecorder.record(clientKey, payload.bundleHash(), DeliveryChannel.REMOTE_EVAL, userAgent);
        return ResponseEntity.ok()
                .header(BUNDLE_HEADER, payload.bundleHash())
                .header(SSE_SUPPORT_HEADER, "enabled")
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    /**
     * The GrowthBook SDK request body.
     *
     * @param forcedFeatures pairs {@code [featureKey, value]} as the SDKs send them
     */
    public record RemoteEvalRequest(JsonNode attributes, Map<String, Integer> forcedVariations, List<List<JsonNode>> forcedFeatures,
                                    String url) {

        Map<String, JsonNode> forcedFeatureMap() {
            Map<String, JsonNode> map = new HashMap<>();
            if (forcedFeatures != null) {
                forcedFeatures.stream().filter(pair -> pair != null && pair.size() == 2 && pair.get(0) != null)
                        .forEach(pair -> map.put(pair.get(0).asText(), pair.get(1)));
            }
            return map;
        }
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
