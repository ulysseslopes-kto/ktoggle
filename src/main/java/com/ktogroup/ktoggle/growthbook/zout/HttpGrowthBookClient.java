package com.ktogroup.ktoggle.growthbook.zout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.commons.exception.UpstreamException;
import com.ktogroup.ktoggle.commons.exception.ValidationException;
import com.ktogroup.ktoggle.growthbook.GrowthBookClient;
import com.ktogroup.ktoggle.growthbook.GrowthBookProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** JDK HTTP client for GrowthBook's REST API (v1, paginated) and SDK endpoint. Read-only: it never sends writes. */
@Component
@RequiredArgsConstructor
public class HttpGrowthBookClient implements GrowthBookClient {

    private static final int PAGE = 100;
    private static final int MAX_PAGES = 200;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final GrowthBookProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public List<JsonNode> list(String collection) {
        requireRestApi();
        List<JsonNode> items = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode body;
            try {
                body = get(properties.apiHost() + "/api/v1/" + collection + "?limit=" + PAGE + "&offset=" + offset, true)
                        .orElseThrow(() -> new UpstreamException("GrowthBook returned no " + collection));
            } catch (BadRequest e) {
                // some collections (environments, attributes) are not paginated and reject limit/offset
                firstArray(get(properties.apiHost() + "/api/v1/" + collection, true)
                        .orElseThrow(() -> new UpstreamException("GrowthBook returned no " + collection))).forEach(items::add);
                return items;
            }
            JsonNode array = firstArray(body);
            array.forEach(items::add);
            if (!body.path("hasMore").asBoolean(false) || array.isEmpty()) {
                return items;
            }
            offset = body.path("nextOffset").asInt(offset + array.size());
        }
        throw new UpstreamException("Too many pages of " + collection);
    }

    @Override
    public Optional<JsonNode> experiment(String id) {
        requireRestApi();
        return get(properties.apiHost() + "/api/v1/experiments/" + URLEncoder.encode(id, StandardCharsets.UTF_8), true)
                .map(body -> body.path("experiment"));
    }

    @Override
    public Optional<JsonNode> sdkPayload(String clientKey) {
        if (!properties.configured()) {
            throw ValidationException.of("GrowthBook is not configured (ktoggle.growthbook.api-host)");
        }
        // GrowthBook answers 400 "Invalid API key" (not 404) for client keys it does not know
        return get(properties.sdkHost() + "/api/features/" + URLEncoder.encode(clientKey, StandardCharsets.UTF_8), false, true);
    }

    private Optional<JsonNode> get(String url, boolean authenticated) {
        return get(url, authenticated, false);
    }

    /** @param clientErrorMeansMissing treat 400/401/403 like 404 (unknown SDK client key) */
    private Optional<JsonNode> get(String url, boolean authenticated, boolean clientErrorMeansMissing) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET()
                .header("Accept", "application/json").header("User-Agent", "ktoggle-migration/1");
        if (authenticated) {
            request.header("Authorization", "Bearer " + properties.secretKey());
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 404 || (clientErrorMeansMissing && (status == 400 || status == 401 || status == 403))) {
                return Optional.empty();
            }
            if (response.statusCode() == 400 && url.contains("?")) {
                throw new BadRequest();
            }
            if (response.statusCode() / 100 != 2) {
                // never echo the body: it could quote the request, including the key
                throw new UpstreamException("GrowthBook answered HTTP " + response.statusCode() + " for " + stripQuery(url));
            }
            return Optional.of(objectMapper.readTree(response.body()));
        } catch (IOException e) {
            throw new UpstreamException("GrowthBook unreachable at " + stripQuery(url) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("Interrupted while calling GrowthBook");
        }
    }

    private void requireRestApi() {
        if (!properties.configured() || properties.secretKey() == null) {
            throw ValidationException.of("GrowthBook import needs ktoggle.growthbook.api-host and ktoggle.growthbook.secret-key");
        }
    }

    /** The list endpoints wrap their items under a named array ({@code features}, {@code savedGroups}...). */
    private static JsonNode firstArray(JsonNode body) {
        for (JsonNode value : body) {
            if (value.isArray()) {
                return value;
            }
        }
        return body.isArray() ? body : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    }

    /** A paginated request was refused: the caller retries without pagination. */
    private static final class BadRequest extends RuntimeException {
        BadRequest() {
            super(null, null, false, false);
        }
    }

    private static String stripQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }
}
