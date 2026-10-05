package com.ktogroup.ktoggle.bundle.zout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.bundle.ActivationNotifier;
import com.ktogroup.ktoggle.bundle.BundleActivatedEvent;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/** Fan-out of activations to every pod through Redis pub/sub. Losing a message is tolerated (periodic resync). */
@Slf4j
@Component
@ConditionalOnProperty(name = "ktoggle.notifier", havingValue = "redis", matchIfMissing = true)
public class RedisActivationNotifier implements ActivationNotifier, MessageListener {

    static final String CHANNEL = "ktoggle:bundle-activated";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final RedisMessageListenerContainer container;

    public RedisActivationNotifier(StringRedisTemplate redis, ObjectMapper objectMapper, ApplicationEventPublisher events,
                                   RedisConnectionFactory connectionFactory) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.events = events;
        this.container = new RedisMessageListenerContainer();
        this.container.setConnectionFactory(connectionFactory);
        this.container.addMessageListener(this, new ChannelTopic(CHANNEL));
        this.container.afterPropertiesSet();
        this.container.start();
    }

    @Override
    public void notifyActivated(String clientKey, String bundleHash) {
        try {
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(new BundleActivatedEvent(clientKey, bundleHash)));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Could not broadcast activation of {} for {}; pods will catch up on next resync", bundleHash, clientKey, e);
            events.publishEvent(new BundleActivatedEvent(clientKey, bundleHash));
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            events.publishEvent(objectMapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), BundleActivatedEvent.class));
        } catch (Exception e) {
            log.warn("Ignoring malformed activation message", e);
        }
    }

    @PreDestroy
    void stop() throws Exception {
        container.destroy();
    }
}
