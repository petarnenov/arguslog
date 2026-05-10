package org.arguslog.api.admin.domain;

import java.time.Instant;
import java.util.UUID;

/** Snapshot of an active or recently-active bonus grant on an org. */
public record BonusGrant(
    Instant until, Instant grantedAt, UUID grantedBy, String grantedByEmail, String reason) {}
