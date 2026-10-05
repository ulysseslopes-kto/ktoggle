package com.ktogroup.ktoggle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KtoggleApplication {

    public static void main(String[] args) {
        SpringApplication.run(KtoggleApplication.class, args);
    }
}
