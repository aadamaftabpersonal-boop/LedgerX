package com.ledgerx;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real PostgreSQL via Testcontainers: locking and constraints cannot be tested against H2.
 * One container per JVM, shared by every test class. Spring caches the application context across
 * classes, so a container that stopped after each class would leave later classes with a dead pool.
 * Testcontainers' Ryuk sidecar removes the container when the JVM exits.
 */
@SpringBootTest(properties = {"ledgerx.admin-key=test-admin-key", "ledgerx.outbox.enabled=false", "ledgerx.rate-limit.enabled=false"})
public abstract class AbstractPostgresIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
