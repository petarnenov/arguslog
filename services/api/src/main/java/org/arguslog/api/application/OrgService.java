package org.arguslog.api.application;

import java.util.List;
import java.util.UUID;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.OrgWriteRepository;
import org.arguslog.api.billing.application.port.OrgPlanRepository;
import org.arguslog.api.billing.domain.PlanTier;
import org.arguslog.api.domain.Org;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrgService implements OrgUseCase {

  static final int MIN_NAME = 2;
  static final int MAX_NAME = 100;

  private final OrgWriteRepository orgs;
  private final MembershipRepository memberships;
  private final OrgPlanRepository plans;

  public OrgService(
      OrgWriteRepository orgs, MembershipRepository memberships, OrgPlanRepository plans) {
    this.orgs = orgs;
    this.memberships = memberships;
    this.plans = plans;
  }

  @Override
  @Transactional
  public Org create(UUID actorId, String name) {
    requireName(name);
    if (actorId == null) {
      throw new IllegalStateException("create called without an authenticated user");
    }
    // Compute the creator's highest active plan once — it drives BOTH the org-cap check and
    // the new org's inherited plan tier (GH #38). First-time creators default to FREE.
    PlanTier inherited = plans.findHighestPlanForOwner(actorId).orElse(PlanTier.FREE);
    requireOrgCapAvailable(actorId, inherited);
    Org org = orgs.create(slugify(name), name.trim(), inherited.dbValue());
    orgs.addMember(org.id(), actorId, "owner");
    return org;
  }

  /**
   * Enforces the per-user owner-org cap. Tier comes from the highest-paid org the user already
   * owns; first-time users default to {@link PlanTier#FREE}. Members of someone else's org are
   * unaffected — only the {@code owner} role contributes to the count.
   */
  private void requireOrgCapAvailable(UUID actorId, PlanTier tier) {
    int cap = tier.orgCap();
    if (cap == Integer.MAX_VALUE) return;
    int existing = orgs.countOwnedBy(actorId);
    if (existing >= cap) {
      throw new OrgQuotaExceededException(
          "Your "
              + tier.dbValue()
              + " plan allows "
              + cap
              + " organization"
              + (cap == 1 ? "" : "s")
              + " per user. Upgrade an existing organization to create another, or get invited to a teammate's workspace.");
    }
  }

  @Override
  @Transactional(readOnly = true)
  public List<Org> listForUser(UUID userId) {
    return orgs.listForUser(userId);
  }

  @Override
  @Transactional
  public boolean delete(UUID actorId, long orgId) {
    String role =
        memberships
            .userRoleInOrg(actorId, orgId)
            .orElseThrow(
                () -> new OrgAccessDeniedException("You are not a member of this organization."));
    if (!"owner".equals(role)) {
      throw new OrgAccessDeniedException("Only org owners can delete an organization.");
    }
    return orgs.delete(orgId);
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static void requireName(String name) {
    if (name == null) {
      throw new InvalidOrgException("name is required");
    }
    String trimmed = name.trim();
    if (trimmed.length() < MIN_NAME) {
      throw new InvalidOrgException("name must be at least " + MIN_NAME + " characters");
    }
    if (trimmed.length() > MAX_NAME) {
      throw new InvalidOrgException("name must be at most " + MAX_NAME + " characters");
    }
  }

  /**
   * Lowercase-ASCII kebab. Non-alphanumeric runs collapse to a single hyphen; leading/trailing
   * hyphens stripped. Falls back to {@code "org"} for inputs that produce an empty slug (e.g. only
   * non-ASCII characters); subsequent collisions surface to the user as a 409 conflict.
   */
  static String slugify(String name) {
    String lower = name.trim().toLowerCase();
    StringBuilder out = new StringBuilder(lower.length());
    boolean prevHyphen = false;
    for (int i = 0; i < lower.length(); i++) {
      char c = lower.charAt(i);
      boolean alnum = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
      if (alnum) {
        out.append(c);
        prevHyphen = false;
      } else if (!prevHyphen && out.length() > 0) {
        out.append('-');
        prevHyphen = true;
      }
    }
    while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
      out.deleteCharAt(out.length() - 1);
    }
    String slug = out.toString();
    return slug.isEmpty() ? "org" : slug;
  }
}
