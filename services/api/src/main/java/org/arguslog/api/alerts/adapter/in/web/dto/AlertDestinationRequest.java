package org.arguslog.api.alerts.adapter.in.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Wire format for create + update. {@code kind} is only honored on create — updates can't change
 * the kind, since dispatchers index by it.
 */
public record AlertDestinationRequest(String kind, String name, JsonNode config) {}
