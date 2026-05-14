package org.arguslog.api.releases.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.arguslog.api.releases.application.ReleaseUseCase.DuplicateReleaseException;
import org.arguslog.api.releases.application.ReleaseUseCase.InvalidReleaseException;
import org.arguslog.api.releases.application.ReleaseUseCase.ReleaseNotFoundException;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.domain.Release;
import org.arguslog.api.releases.domain.ReleaseInput;
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
    when(repository.create(eq(101L), argThat(i -> i != null && "1.2.3".equals(i.version()))))
        .thenReturn(stored);

    Release out = service.create(101L, ReleaseInput.versionOnly("  1.2.3  "));

    assertThat(out).isEqualTo(stored);
    verify(repository)
        .create(eq(101L), argThat(i -> "1.2.3".equals(i.version()) && i.gitSha() == null));
  }

  @Test
  void metadataFieldsAreTrimmedAndForwarded() {
    Instant released = Instant.parse("2026-05-05T10:00:00Z");
    Release stored = new Release(7L, 101L, "1.2.3", Instant.parse("2026-05-05T12:00:00Z"));
    when(repository.create(eq(101L), any(ReleaseInput.class))).thenReturn(stored);

    service.create(
        101L,
        new ReleaseInput(
            "1.2.3", released, "  abc123  ", "  main  ", "  production  ", "ship notes"));

    verify(repository)
        .create(
            eq(101L),
            argThat(
                i ->
                    "abc123".equals(i.gitSha())
                        && "main".equals(i.gitRef())
                        && "production".equals(i.deployStage())
                        && "ship notes".equals(i.changelog())
                        && released.equals(i.releasedAt())));
  }

  @Test
  void blankMetadataNormalizesToNull() {
    Release stored = new Release(7L, 101L, "1.2.3", Instant.parse("2026-05-05T12:00:00Z"));
    when(repository.create(eq(101L), any(ReleaseInput.class))).thenReturn(stored);

    service.create(101L, new ReleaseInput("1.2.3", null, "  ", "", "   ", null));

    verify(repository)
        .create(
            eq(101L),
            argThat(
                i ->
                    i.gitSha() == null
                        && i.gitRef() == null
                        && i.deployStage() == null
                        && i.changelog() == null));
  }

  @Test
  void blankOrNullVersionRejectedBeforeRepository() {
    assertThatThrownBy(() -> service.create(101L, ReleaseInput.versionOnly(null)))
        .isInstanceOf(InvalidReleaseException.class);
    assertThatThrownBy(() -> service.create(101L, ReleaseInput.versionOnly("   ")))
        .isInstanceOf(InvalidReleaseException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void overlongVersionRejected() {
    String tooLong = "v".repeat(ReleaseService.MAX_VERSION_LENGTH + 1);
    assertThatThrownBy(() -> service.create(101L, ReleaseInput.versionOnly(tooLong)))
        .isInstanceOf(InvalidReleaseException.class)
        .hasMessageContaining(String.valueOf(ReleaseService.MAX_VERSION_LENGTH));
    verifyNoInteractions(repository);
  }

  @Test
  void overlongGitShaRejected() {
    String tooLong = "a".repeat(ReleaseService.MAX_GIT_SHA_LENGTH + 1);
    assertThatThrownBy(
            () ->
                service.create(101L, new ReleaseInput("1.2.3", null, tooLong, null, null, null)))
        .isInstanceOf(InvalidReleaseException.class)
        .hasMessageContaining("gitSha");
    verifyNoInteractions(repository);
  }

  @Test
  void duplicateRowSurfacesAsDuplicateReleaseException() {
    when(repository.create(eq(101L), any(ReleaseInput.class)))
        .thenThrow(new DuplicateKeyException("uq violation"));

    assertThatThrownBy(() -> service.create(101L, ReleaseInput.versionOnly("1.2.3")))
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

  @Test
  void updateTrimsAndPersists() {
    Release after = new Release(7L, 101L, "2.0.0", Instant.parse("2026-05-05T12:00:00Z"));
    when(repository.update(
            eq(101L), eq(7L), argThat(i -> i != null && "2.0.0".equals(i.version()))))
        .thenReturn(Optional.of(after));

    Release out = service.update(101L, 7L, ReleaseInput.versionOnly("  2.0.0  "));

    assertThat(out).isEqualTo(after);
    verify(repository)
        .update(eq(101L), eq(7L), argThat(i -> "2.0.0".equals(i.version())));
  }

  @Test
  void updateRejectsBlank() {
    assertThatThrownBy(() -> service.update(101L, 7L, ReleaseInput.versionOnly("  ")))
        .isInstanceOf(InvalidReleaseException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void updateRejectsTooLong() {
    String tooLong = "v".repeat(ReleaseService.MAX_VERSION_LENGTH + 1);
    assertThatThrownBy(() -> service.update(101L, 7L, ReleaseInput.versionOnly(tooLong)))
        .isInstanceOf(InvalidReleaseException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void updateThrowsNotFoundWhenRepoMisses() {
    when(repository.update(eq(101L), eq(99L), any(ReleaseInput.class))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.update(101L, 99L, ReleaseInput.versionOnly("x")))
        .isInstanceOf(ReleaseNotFoundException.class);
  }

  @Test
  void updateSurfacesDuplicateAsDomainException() {
    when(repository.update(eq(101L), eq(7L), any(ReleaseInput.class)))
        .thenThrow(new DuplicateKeyException("uq violation"));
    assertThatThrownBy(() -> service.update(101L, 7L, ReleaseInput.versionOnly("1.0.0")))
        .isInstanceOf(DuplicateReleaseException.class);
  }

  @Test
  void deleteReturnsRepoOutcome() {
    when(repository.delete(101L, 7L)).thenReturn(true);
    assertThat(service.delete(101L, 7L)).isTrue();

    when(repository.delete(101L, 99L)).thenReturn(false);
    assertThat(service.delete(101L, 99L)).isFalse();
  }
}
