package com.fairchain.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 예제서버 (콘서트 예매 사이트) */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ExampleServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExampleServerApplication.class, args);
    }
}
