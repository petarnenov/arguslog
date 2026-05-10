package org.arguslog.api.admin.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import java.util.UUID;
import org.arguslog.api.admin.domain.AdminAuditEntry;

public record AdminAuditResponse(
    @JsonProperty("id") long id,
    @JsonProperty("ts") Instant ts,
    @JsonProperty("adminUser") UUID adminUser,
    @JsonProperty("adminEmail") String adminEmail,
    @JsonProperty("action") String action,
    @JsonProperty("targetType") String targetType,
    @JsonProperty("targetId") String targetId,
    @JsonProperty("payload") @JsonRawValue String payload) {

  public static AdminAuditResponse from(AdminAuditEntry e) {
    return new AdminAuditResponse(
        e.id(),
        e.ts(),
        e.adminUser(),
        e.adminEmail(),
        e.action(),
        e.targetType(),
        e.targetId(),
        e.payloadJson() == null || e.payloadJson().isBlank() ? "{}" : e.payloadJson());
  }
}
