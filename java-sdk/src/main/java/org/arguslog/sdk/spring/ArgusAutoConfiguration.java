package org.arguslog.sdk.spring;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.arguslog.sdk.Argus;
import org.arguslog.sdk.ArgusOptions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@AutoConfiguration
@EnableConfigurationProperties(ArgusProperties.class)
@ConditionalOnProperty(prefix = "arguslog", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ArgusAutoConfiguration {

  private final ArgusProperties properties;

  public ArgusAutoConfiguration(ArgusProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void init() {
    if (properties.getDsn() == null || properties.getDsn().isBlank()) {
      return;
    }
    Argus.init(
        ArgusOptions.builder()
            .dsn(properties.getDsn())
            .environment(properties.getEnvironment())
            .release(properties.getRelease())
            .sampleRate(properties.getSampleRate())
            .maxQueueSize(properties.getMaxQueueSize())
            .scrubbingEnabled(properties.isScrubbing())
            .debug(properties.isDebug())
            .build());
  }

  @PreDestroy
  void shutdown() {
    Argus.close();
  }
}
