package org.arguslog.api.releases.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Wire format for requesting an upload slot. {@code sha256} is 64 lowercase hex chars. */
public record SourceMapArtifactRequest(
    @JsonProperty("originalPath") String originalPath,
    String sha256,
    @JsonProperty("sizeBytes") long sizeBytes) {}
