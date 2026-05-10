package org.arguslog.api.admin.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Generic page envelope for admin list endpoints. */
public record AdminPageResponse<T>(
    @JsonProperty("items") List<T> items,
    @JsonProperty("total") long total,
    @JsonProperty("offset") int offset,
    @JsonProperty("limit") int limit) {}
