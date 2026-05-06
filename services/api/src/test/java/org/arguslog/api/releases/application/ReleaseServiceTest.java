package org.arguslog.api.releases.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.arguslog.api.releases.application.ReleaseUseCase.DuplicateReleaseException;
import org.arguslog.api.releases.application.ReleaseUseCase.InvalidReleaseException;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.domain.Release;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class ReleaseServiceTest {

  @Mock ReleaseRepository repository;

  ReleaseService service;

  @BeforeEach
  void setUp() {
    service = new ReleaseService(repository);
  }

  @Test
  void createTrimsAndPersists() {
    Release stored = new Release(7L, 101L, "1.2.3", Instant.parse("2026-05-05T12:00:00Z"));
    when(repository.create(101L, "1.2.3")).thenReturn(stored);

    Release out = service.create(101L, "  1.2.3  ");

    assertThat(out).isEqualTo(stored);
    verify(repository).create(101L, "1.2.3");
  }

  @Test
  void blankOrNullVersionRejectedBeforeRepository() {
    assertThatThrownBy(() -> service.create(101L, null))
        .isInstanceOf(InvalidReleaseException.class);
    assertThatThrownBy(() -> service.create(101L, "   "))
        .isInstanceOf(InvalidReleaseException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void overlongVersionRejected() {
    String tooLong = "v".repeat(ReleaseService.MAX_VERSION_LENGTH + 1);
    assertThatThrownBy(() -> service.create(101L, tooLong))
        .isInstanceOf(InvalidReleaseException.class)
        .hasMessageContaining(String.valueOf(ReleaseService.MAX_VERSION_LENGTH));
    verifyNoInteractions(repository);
  }

  @Test
  void duplicateRowSurfacesAsDuplicateReleaseException() {
    when(repository.create(101L, "1.2.3")).thenThrow(new DuplicateKeyException("uq violation"));

    assertThatThrownBy(() -> service.create(101L, "1.2.3"))
        .isInstanceOf(DuplicateReleaseException.class)
        .hasMessageContaining("1.2.3");
  }

  @Test
  void listDelegates() {
    Release a = new Release(1L, 101L, "1.0.0", Instant.parse("2026-05-05T11:00:00Z"));
    Release b = new Release(2L, 101L, "1.0.1", Instant.parse("2026-05-05T12:00:00Z"));
    when(repository.listForProject(101L)).thenReturn(List.of(b, a));

    assertThat(service.list(101L)).containsExactly(b, a);
  }

  @Test
  void getDelegates() {
    Release stored = new Release(7L, 101L, "1.2.3", Instant.parse("2026-05-05T12:00:00Z"));
    when(repository.find(101L, 7L)).thenReturn(Optional.of(stored));

    assertThat(service.get(101L, 7L)).contains(stored);
  }
}
