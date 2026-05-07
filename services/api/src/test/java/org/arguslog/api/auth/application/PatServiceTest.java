package org.arguslog.api.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.arguslog.api.auth.application.PatUseCase.InvalidPatException;
import org.arguslog.api.auth.application.PatUseCase.Issued;
import org.arguslog.api.auth.application.port.PatRepository;
import org.arguslog.api.auth.application.port.PatRepository.PatRow;
import org.arguslog.api.auth.application.port.TokenHasher;
import org.arguslog.api.auth.domain.PatScope;
import org.arguslog.api.auth.domain.PersonalAccessToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PatServiceTest {

  @Mock PatRepository repository;
  @Mock TokenHasher hasher;

  PatService service;

  private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-05-05T12:00:00Z");

  @BeforeEach
  void setUp() {
    // Deterministic RNG so the prefix/secret are reproducible across runs.
    service =
        new PatService(
            repository, hasher, Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(new byte[] {1}));
  }

  @Test
  void mintReturnsPlaintextOnceAndPersistsArgon2HashOfFullToken() {
    when(hasher.hash(anyString())).thenReturn("$argon2id$v=19$...");
    when(repository.create(eq(USER), eq("ci-bot"), anyString(), anyString(), eq(null), eq(null)))
        .thenAnswer(
            inv ->
                new PersonalAccessToken(
                    7L, USER, inv.getArgument(1), inv.getArgument(2), null, null, NOW, null));

    Issued out = service.create(USER, "ci-bot", null, null);

    assertThat(out.plaintext()).startsWith("arglog_pat_");
    String[] parts = out.plaintext().substring("arglog_pat_".length()).split("_");
    assertThat(parts).hasSize(2);
    assertThat(parts[0]).hasSize(PatService.PREFIX_LENGTH);
    assertThat(parts[1]).hasSize(PatService.SECRET_LENGTH);

    ArgumentCaptor<String> hashedPlaintext = ArgumentCaptor.forClass(String.class);
    verify(hasher).hash(hashedPlaintext.capture());
    // Hash input is the full wire token, not just the secret.
    assertThat(hashedPlaintext.getValue()).isEqualTo(out.plaintext());
  }

  @Test
  void mintWithExplicitScopesPassesThemToRepository() {
    when(hasher.hash(anyString())).thenReturn("$argon2id$v=19$...");
    Set<PatScope> requested = EnumSet.of(PatScope.RELEASES_WRITE, PatScope.SOURCEMAPS_WRITE);
    when(repository.create(
            eq(USER), eq("ci-bot"), anyString(), anyString(), eq(null), eq(requested)))
        .thenAnswer(
            inv ->
                new PersonalAccessToken(
                    7L,
                    USER,
                    inv.getArgument(1),
                    inv.getArgument(2),
                    null,
                    null,
                    NOW,
                    inv.getArgument(5)));

    Issued out = service.create(USER, "ci-bot", null, requested);
    assertThat(out.token().scopes()).containsExactlyInAnyOrderElementsOf(requested);
  }

  @Test
  void mintWithEmptyScopeSetIsRejected() {
    assertThatThrownBy(() -> service.create(USER, "ci-bot", null, Set.of()))
        .isInstanceOf(InvalidPatException.class)
        .hasMessageContaining("scopes");
    verify(hasher, never()).hash(any());
  }

  @Test
  void blankNameRejectedBeforeHash() {
    assertThatThrownBy(() -> service.create(USER, "  ", null, null))
        .isInstanceOf(InvalidPatException.class);
    verify(hasher, never()).hash(any());
  }

  @Test
  void overlongNameRejected() {
    String tooLong = "a".repeat(PatService.MAX_NAME_LENGTH + 1);
    assertThatThrownBy(() -> service.create(USER, tooLong, null, null))
        .isInstanceOf(InvalidPatException.class);
  }

  @Test
  void verifyReturnsTokenAndBumpsLastUsedOnSuccess() {
    String wire = "arglog_pat_AAAAAAAA_" + "x".repeat(48);
    PersonalAccessToken stored =
        new PersonalAccessToken(7L, USER, "ci", "AAAAAAAA", null, null, NOW.minusSeconds(60), null);
    when(repository.findByPrefix("AAAAAAAA")).thenReturn(Optional.of(new PatRow(stored, "$h$")));
    when(hasher.matches(wire, "$h$")).thenReturn(true);

    Optional<PersonalAccessToken> out = service.verify(wire, NOW);
    assertThat(out).contains(stored);
    verify(repository).recordUsage(7L, NOW);
  }

  @Test
  void verifyRejectsExpiredToken() {
    String wire = "arglog_pat_AAAAAAAA_" + "x".repeat(48);
    PersonalAccessToken expired =
        new PersonalAccessToken(
            7L, USER, "ci", "AAAAAAAA", NOW.minusSeconds(1), null, NOW.minusSeconds(60), null);
    when(repository.findByPrefix("AAAAAAAA")).thenReturn(Optional.of(new PatRow(expired, "$h$")));

    assertThat(service.verify(wire, NOW)).isEmpty();
    verify(hasher, never()).matches(anyString(), anyString());
    verify(repository, never()).recordUsage(anyLong(), any());
  }

  @Test
  void verifyRejectsHashMismatch() {
    String wire = "arglog_pat_AAAAAAAA_" + "x".repeat(48);
    PersonalAccessToken stored =
        new PersonalAccessToken(7L, USER, "ci", "AAAAAAAA", null, null, NOW, null);
    when(repository.findByPrefix("AAAAAAAA")).thenReturn(Optional.of(new PatRow(stored, "$h$")));
    when(hasher.matches(wire, "$h$")).thenReturn(false);

    assertThat(service.verify(wire, NOW)).isEmpty();
    verify(repository, never()).recordUsage(anyLong(), any());
  }

  @Test
  void verifyRejectsUnknownPrefix() {
    String wire = "arglog_pat_AAAAAAAA_" + "x".repeat(48);
    when(repository.findByPrefix("AAAAAAAA")).thenReturn(Optional.empty());
    assertThat(service.verify(wire, NOW)).isEmpty();
  }

  @Test
  void verifyRejectsMalformedTokens() {
    assertThat(service.verify(null, NOW)).isEmpty();
    assertThat(service.verify("", NOW)).isEmpty();
    assertThat(service.verify("Bearer foo", NOW)).isEmpty();
    assertThat(service.verify("arglog_pat_TOOSHORT", NOW)).isEmpty();
    // Prefix segment must be exactly 8 chars before the underscore.
    assertThat(service.verify("arglog_pat_TOOLONGPREFIX_secret", NOW)).isEmpty();
  }

  @Test
  void revokeIsScopedToOwner() {
    when(repository.deleteForUser(USER, 7L)).thenReturn(true);
    assertThat(service.revoke(USER, 7L)).isTrue();
  }
}
