package org.arguslog.api.domain;

import java.time.Instant;

public record Org(long id, String slug, String name, String plan, Instant createdAt) {}
