package com.mwb.ai.claw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot Starter
 *
 * @author Frank Zhang
 */
@SpringBootApplication(scanBasePackages = {"com.mwb.ai.claw"})
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
