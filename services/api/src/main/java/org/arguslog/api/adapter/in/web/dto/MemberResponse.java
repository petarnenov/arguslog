package org.arguslog.api.adapter.in.web.dto;

import java.time.Instant;
import java.util.UUID;
import org.arguslog.api.domain.Member;

public record MemberResponse(
    UUID userId, String email, String displayName, String role, Instant addedAt) {

  public static MemberResponse from(Member m) {
    return new MemberResponse(m.userId(), m.email(), m.displayName(), m.role(), m.addedAt());
  }
}
