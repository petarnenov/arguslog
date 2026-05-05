package org.arguslog.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.arguslog.api.application.ProjectUseCase.InvalidProjectException;
import org.arguslog.api.application.port.ProjectWriteRepository;
import org.arguslog.api.domain.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

  @Mock ProjectWriteRepository projects;

  ProjectService service;

  @BeforeEach
  void setUp() {
    service = new ProjectService(projects);
  }

  @Test
  void createDerivesSlugAndPassesPlatform() {
    Project expected =
        new Project(7L, 1L, "my-app", "My App", "javascript", Instant.parse("2026-05-06T00:00:00Z"));
    when(projects.create(eq(1L), eq("my-app"), eq("My App"), eq("javascript"))).thenReturn(expected);

    Project out = service.create(1L, "My App", "javascript");

    assertThat(out).isEqualTo(expected);
    verify(projects).create(1L, "my-app", "My App", "javascript");
  }

  @Test
  void rejectsBlankName() {
    assertThatThrownBy(() -> service.create(1L, " ", "javascript"))
        .isInstanceOf(InvalidProjectException.class)
        .hasMessageContaining("at least");
    verify(projects, never()).create(anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  void rejectsUnknownPlatform() {
    assertThatThrownBy(() -> service.create(1L, "Acme", "cobol"))
        .isInstanceOf(InvalidProjectException.class)
        .hasMessageContaining("platform");
    verify(projects, never()).create(anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  void acceptsAllKnownPlatforms() {
    Project p =
        new Project(1L, 1L, "x", "x", "x", Instant.EPOCH);
    when(projects.create(anyLong(), anyString(), anyString(), anyString())).thenReturn(p);
    service.create(1L, "ok", "javascript");
    service.create(1L, "ok", "react");
    service.create(1L, "ok", "java-spring");
  }
}
