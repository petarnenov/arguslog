package org.arguslog.api.admin.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PlatformAdminProperties.class)
public class AdminConfig {}
