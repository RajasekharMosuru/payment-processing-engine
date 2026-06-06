package com.rajasekhar.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PaymentProcessingEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentProcessingEngineApplication.class, args);
    }
}
