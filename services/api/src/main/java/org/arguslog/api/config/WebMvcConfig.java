package org.arguslog.api.config;

import org.arguslog.api.security.OrgAccessGuard;
import org.arguslog.api.security.ProjectAccessGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

  private final ObjectProvider<ProjectAccessGuard> projectGuard;
  private final ObjectProvider<OrgAccessGuard> orgGuard;

  public WebMvcConfig(
      ObjectProvider<ProjectAccessGuard> projectGuard, ObjectProvider<OrgAccessGuard> orgGuard) {
    this.projectGuard = projectGuard;
    this.orgGuard = orgGuard;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    // ObjectProvider so the test profile (which excludes both guards) doesn't blow up here.
    ProjectAccessGuard p = projectGuard.getIfAvailable();
    if (p != null) {
      registry.addInterceptor(p).addPathPatterns("/api/v1/projects/**");
    }
    OrgAccessGuard o = orgGuard.getIfAvailable();
    if (o != null) {
      registry.addInterceptor(o).addPathPatterns("/api/v1/orgs/**");
    }
  }
}
