package org.arguslog.api.config;

import org.arguslog.api.security.JwtUserSyncInterceptor;
import org.arguslog.api.security.OrgAccessGuard;
import org.arguslog.api.security.ProjectAccessGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

  private final ObjectProvider<JwtUserSyncInterceptor> jwtSync;
  private final ObjectProvider<ProjectAccessGuard> projectGuard;
  private final ObjectProvider<OrgAccessGuard> orgGuard;

  public WebMvcConfig(
      ObjectProvider<JwtUserSyncInterceptor> jwtSync,
      ObjectProvider<ProjectAccessGuard> projectGuard,
      ObjectProvider<OrgAccessGuard> orgGuard) {
    this.jwtSync = jwtSync;
    this.projectGuard = projectGuard;
    this.orgGuard = orgGuard;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    // ObjectProvider so the test profile (which excludes both guards) doesn't blow up here.
    // Order matters: JwtUserSyncInterceptor must run first so a freshly-signed-in invitee gets
    // their placeholder user row realigned to their JWT sub before the membership guards check it.
    JwtUserSyncInterceptor sync = jwtSync.getIfAvailable();
    if (sync != null) {
      registry.addInterceptor(sync).addPathPatterns("/api/v1/**");
    }
    ProjectAccessGuard p = projectGuard.getIfAvailable();
    if (p != null) {
      registry.addInterceptor(p).addPathPatterns("/api/v1/projects/**");
    }
    OrgAccessGuard o = orgGuard.getIfAvailable();
    if (o != null) {
      // {orgId}-bearing routes only — POST /api/v1/orgs and GET /api/v1/orgs (list-mine) need to
      // bypass the guard since the caller has no orgId yet, but stay authenticated via Spring
      // Security's filter chain.
      registry.addInterceptor(o).addPathPatterns("/api/v1/orgs/*/**");
    }
  }
}
