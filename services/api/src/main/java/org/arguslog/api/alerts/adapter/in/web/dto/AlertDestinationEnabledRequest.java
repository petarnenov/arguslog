package org.arguslog.api.alerts.adapter.in.web.dto;

/**
 * Body for {@code PATCH /api/v1/orgs/{orgId}/alert-destinations/{id}/enabled}. The toggle is its
 * own endpoint (rather than overloaded onto the PUT used for name + config) so the dashboard's
 * pause switch doesn't have to re-supply the encrypted config blob the UI never shows back.
 */
public record AlertDestinationEnabledRequest(Boolean enabled) {}
