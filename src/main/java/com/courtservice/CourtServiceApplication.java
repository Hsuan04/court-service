package com.courtservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CourtServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CourtServiceApplication.class, args);
    }

}
