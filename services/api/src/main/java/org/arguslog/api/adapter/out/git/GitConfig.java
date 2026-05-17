package org.arguslog.api.adapter.out.git;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the {@code arguslog.git.public.*} block as a typed properties bean. */
@Configuration
@EnableConfigurationProperties(GitPublicProperties.class)
public class GitConfig {}
