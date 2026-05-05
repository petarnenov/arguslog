package dev.argus.api.config;

import dev.argus.api.security.ProjectAccessGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

  private final ObjectProvider<ProjectAccessGuard> guard;

  public WebMvcConfig(ObjectProvider<ProjectAccessGuard> guard) {
    this.guard = guard;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    // ObjectProvider so the test profile (which excludes the guard) doesn't blow up here.
    ProjectAccessGuard maybe = guard.getIfAvailable();
    if (maybe != null) {
      registry.addInterceptor(maybe).addPathPatterns("/api/v1/projects/**");
    }
  }
}
