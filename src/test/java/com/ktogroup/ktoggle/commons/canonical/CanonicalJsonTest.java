package com.ktogroup.ktoggle.commons.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.bundle.BundleBody;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * GOLDEN TESTS — never update an expected value in place. A different output means every historical hash
 * (bundles, audit chain, activations, attribute digests) would stop verifying; such a change requires a new
 * contract version.
 */
class CanonicalJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CanonicalJson canonicalJson = new CanonicalJson(objectMapper);

    @Test
    void canonicalizes_rfc8785_reference_vector() {
        String input = "{\"numbers\":[333333333.33333329,1E30,4.50,2e-3,0.000000000000000000000000001],"
                + "\"string\":\"\\u20ac$\\u000F\\u000aA'\\u0042\\u0022\\u005c\\\\\\\"\\/\","
                + "\"literals\":[null,true,false]}";

        assertThat(canonicalJson.canonicalize(input)).isEqualTo(
                "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],"
                        + "\"string\":\"€$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"}");
    }

    @Test
    void sorts_keys_and_removes_whitespace() {
        assertThat(canonicalJson.canonicalize("{ \"b\" : 2, \"a\" : \"x\" }")).isEqualTo("{\"a\":\"x\",\"b\":2}");
        assertThat(Hashes.sha256Hex(canonicalJson.canonicalize(Map.of("b", 2, "a", "x"))))
                .isEqualTo("768ca668c0f84dd39bf269e25c9a3f0af4812e41026b6fead9a2666078ef16f6");
    }

    @Test
    void numerically_equal_values_have_the_same_canonical_form() {
        assertThat(canonicalJson.canonicalize("{\"v\":1.0}")).isEqualTo(canonicalJson.canonicalize("{\"v\":1}"));
        assertThat(canonicalJson.canonicalize("{\"v\":1e2}")).isEqualTo("{\"v\":100}");
    }

    @Test
    void bundle_body_hash_is_frozen() {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode feature = payload.putObject("features").putObject("checkout-v2");
        feature.put("defaultValue", false);
        ObjectNode rule = feature.putArray("rules").addObject();
        rule.put("id", "fr_1");
        rule.putObject("condition").put("country", "BR");
        rule.put("force", true);
        BundleBody body = new BundleBody(BundleBody.CONTRACT_VERSION,
                new BundleBody.Target("sdk-abc12345", "prd", List.of()),
                new BundleBody.Evaluator("growthbook-features", 1, "growthbook-sdk-java@0.10.5"),
                Map.of("checkout-v2", 3), payload);

        assertThat(Hashes.sha256Hex(canonicalJson.canonicalize(body)))
                .isEqualTo("3f3e26d71491b26c857804e60dd38b3685af826fd1156e2ac65a604013697a2d");
    }

    @Test
    void detects_non_canonical_input() {
        assertThat(canonicalJson.isCanonical("{\"a\":1,\"b\":2}")).isTrue();
        assertThat(canonicalJson.isCanonical("{\"b\":2,\"a\":1}")).isFalse();
        assertThat(canonicalJson.isCanonical("{\"a\": 1}")).isFalse();
    }

    @Test
    void hmac_differs_by_key_and_is_stable() {
        String a = Hashes.hmacSha256Hex("k1".getBytes(), "{\"cpf\":\"12345678900\"}");
        assertThat(a).isEqualTo(Hashes.hmacSha256Hex("k1".getBytes(), "{\"cpf\":\"12345678900\"}"));
        assertThat(a).isNotEqualTo(Hashes.hmacSha256Hex("k2".getBytes(), "{\"cpf\":\"12345678900\"}"));
        assertThat(a).isNotEqualTo(Hashes.sha256Hex("{\"cpf\":\"12345678900\"}"));
    }
}
