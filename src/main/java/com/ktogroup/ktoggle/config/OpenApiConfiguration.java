package com.ktogroup.ktoggle.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    private static final String BEARER = "bearer";

    @Bean
    public OpenAPI ktoggleOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ktoggle")
                        .description("KTO feature flags: admin API and GrowthBook-compatible SDK API")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder().group("admin").pathsToMatch("/admin/**").build();
    }

    @Bean
    public GroupedOpenApi sdkApi() {
        return GroupedOpenApi.builder().group("sdk").pathsToMatch("/api/**", "/sub/**").build();
    }
}
