package com.ledgerx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LedgerxApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerxApplication.class, args);
    }
}
