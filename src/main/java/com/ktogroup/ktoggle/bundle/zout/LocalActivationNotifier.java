package com.ktogroup.ktoggle.bundle.zout;

import com.ktogroup.ktoggle.bundle.ActivationNotifier;
import com.ktogroup.ktoggle.bundle.BundleActivatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Single-instance notifier (tests, local runs without Redis). */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ktoggle.notifier", havingValue = "local")
public class LocalActivationNotifier implements ActivationNotifier {

    private final ApplicationEventPublisher events;

    @Override
    public void notifyActivated(String clientKey, String bundleHash) {
        events.publishEvent(new BundleActivatedEvent(clientKey, bundleHash));
    }
}
