package org.arguslog.api.slack.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the {@code arguslog.slack.oauth.*} block as a typed properties bean. */
@Configuration
@EnableConfigurationProperties(SlackOAuthProperties.class)
public class SlackConfig {}
