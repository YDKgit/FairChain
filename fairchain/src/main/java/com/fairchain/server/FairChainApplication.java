package com.fairchain.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FairChainApplication {

    public static void main(String[] args) {
        SpringApplication.run(FairChainApplication.class, args);
    }
}
