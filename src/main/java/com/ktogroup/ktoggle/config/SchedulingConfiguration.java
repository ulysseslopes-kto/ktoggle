package com.ktogroup.ktoggle.config;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs; disabled in tests ({@code ktoggle.scheduling.enabled=false}), which trigger them explicitly. */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
@ConditionalOnProperty(name = "ktoggle.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfiguration {
}
