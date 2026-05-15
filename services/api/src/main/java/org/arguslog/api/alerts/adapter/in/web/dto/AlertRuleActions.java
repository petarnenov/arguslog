package org.arguslog.api.alerts.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Typed shape of the alert rule actions JSON. Today this carries only the list of alert
 * destination ids the rule fans out to; new action kinds (webhook templating, message
 * overrides, …) plug in here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertRuleActions(List<Long> destinationIds) {}
