package org.arguslog.api.releases.adapter.in.web.dto;

/** Wire format for creating a release. {@code version} is required + trimmed server-side. */
public record ReleaseRequest(String version) {}
