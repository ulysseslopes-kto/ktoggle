package com.ktogroup.ktoggle.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.commons.canonical.CanonicalJson;
import java.time.Clock;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class CoreConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public CanonicalJson canonicalJson(ObjectMapper objectMapper) {
        return new CanonicalJson(objectMapper);
    }

    /** Cluster-wide lock so scheduled jobs (reconciliation, archiving, partitions) run on one pod at a time. */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
