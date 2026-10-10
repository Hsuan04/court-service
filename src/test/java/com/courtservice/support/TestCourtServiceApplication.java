package com.courtservice.support;

import com.courtservice.CourtServiceApplication;
import org.springframework.boot.SpringApplication;

public class TestCourtServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(CourtServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
