package com.ktogroup.ktoggle.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.ktogroup.ktoggle.commons.canonical.CanonicalJson;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AttributeDigesterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CanonicalJson CANONICAL = new CanonicalJson(MAPPER);
    private static final String SECRET = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private final AttributeDigester digester = digester(SECRET, "k1");

    @Test
    void digest_ignores_attribute_order() throws Exception {
        assertThat(digester.digest(MAPPER.readTree("{\"a\":1,\"b\":\"x\"}")))
                .isEqualTo(digester.digest(MAPPER.readTree("{\"b\":\"x\",\"a\":1}")))
                .hasSize(64);
    }

    @Test
    void digest_depends_on_the_secret() throws Exception {
        AttributeDigester other = digester(Base64.getEncoder().encodeToString("another-secret-another-secret-00".getBytes()), "k1");

        assertThat(other.digest(MAPPER.readTree("{\"a\":1}"))).isNotEqualTo(digester.digest(MAPPER.readTree("{\"a\":1}")));
    }

    @Test
    void matches_the_digest_of_the_same_attributes_under_the_same_key_id() throws Exception {
        String digest = digester.digest(MAPPER.readTree("{\"userId\":\"123\"}"));

        assertThat(digester.matches(MAPPER.readTree("{\"userId\":\"123\"}"), digest, "k1")).isTrue();
    }

    @Test
    void does_not_match_other_attributes() throws Exception {
        String digest = digester.digest(MAPPER.readTree("{\"userId\":\"123\"}"));

        assertThat(digester.matches(MAPPER.readTree("{\"userId\":\"124\"}"), digest, "k1")).isFalse();
    }

    @Test
    void does_not_match_when_the_digest_was_made_with_another_key_id() throws Exception {
        String digest = digester.digest(MAPPER.readTree("{\"userId\":\"123\"}"));

        assertThat(digester.matches(MAPPER.readTree("{\"userId\":\"123\"}"), digest, "k0")).isFalse();
    }

    @Test
    void missing_attributes_digest_like_an_empty_set() {
        String empty = digester.digest(JsonNodeFactory.instance.objectNode());

        assertThat(digester.digest(null)).isEqualTo(empty);
        assertThat(digester.digest(NullNode.getInstance())).isEqualTo(empty);
    }

    @Test
    void without_a_configured_secret_an_ephemeral_one_is_used_per_instance() throws Exception {
        AttributeDigester first = digester(null, "k1");
        AttributeDigester second = digester(" ", "k1");

        assertThat(first.digest(MAPPER.readTree("{\"a\":1}"))).isNotEqualTo(second.digest(MAPPER.readTree("{\"a\":1}")));
        assertThat(first.keyId()).isEqualTo("k1");
    }

    private static AttributeDigester digester(String secret, String keyId) {
        return new AttributeDigester(CANONICAL, new DecisionProperties(keyId, secret, 0, 0, null, null, 0, 0));
    }
}
