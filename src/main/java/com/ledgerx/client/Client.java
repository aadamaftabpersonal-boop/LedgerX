package com.ledgerx.client;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Client(UUID id, String name, OffsetDateTime createdAt) {}
