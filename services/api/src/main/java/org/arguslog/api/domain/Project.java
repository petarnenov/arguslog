package org.arguslog.api.domain;

import java.time.Instant;

public record Project(
    long id,
    long orgId,
    String slug,
    String name,
    String platform,
    Instant createdAt,
    GitProvider gitProvider,
    String gitRepo) {}
