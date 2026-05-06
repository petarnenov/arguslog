package org.arguslog.worker.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables {@link org.springframework.scheduling.annotation.Scheduled} across the worker. */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
