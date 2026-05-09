package org.arguslog.worker.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.arguslog.worker.billing.application.port.RenewalEmailSender;
import org.arguslog.worker.billing.application.port.RenewalReminderRepository;
import org.arguslog.worker.billing.application.port.RenewalReminderRepository.ReminderCandidate;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RenewalReminderServiceTest {

  private static final Instant NOW = Instant.parse("2026-05-09T12:00:00Z");
  private static final LocalDate TODAY = LocalDate.ofInstant(NOW, ZoneOffset.UTC);

  @Test
  void sendsReminderForEachKindBucket() {
    RenewalReminderRepository repo = Mockito.mock(RenewalReminderRepository.class);
    RenewalEmailSender mailer = Mockito.mock(RenewalEmailSender.class);

    ReminderCandidate cand14 =
        new ReminderCandidate(1L, "acme", "Acme", "ceo@acme.test", TODAY.plusDays(14));
    ReminderCandidate cand7 =
        new ReminderCandidate(2L, "beta", "Beta", "ops@beta.test", TODAY.plusDays(7));
    ReminderCandidate cand1 =
        new ReminderCandidate(3L, "gamma", "Gamma", "owner@gamma.test", TODAY.plusDays(1));

    when(repo.findCandidates(TODAY.plusDays(14), 14)).thenReturn(List.of(cand14));
    when(repo.findCandidates(TODAY.plusDays(7), 7)).thenReturn(List.of(cand7));
    when(repo.findCandidates(TODAY.plusDays(1), 1)).thenReturn(List.of(cand1));
    when(repo.markSent(anyLong(), any(), anyInt())).thenReturn(true);
    when(mailer.send(anyString(), anyString(), anyString(), any(), anyInt())).thenReturn(true);

    RenewalReminderService service =
        new RenewalReminderService(repo, mailer, Clock.fixed(NOW, ZoneOffset.UTC));

    int sent = service.runOnce();

    assertThat(sent).isEqualTo(3);
    verify(mailer).send("ceo@acme.test", "Acme", "acme", TODAY.plusDays(14), 14);
    verify(mailer).send("ops@beta.test", "Beta", "beta", TODAY.plusDays(7), 7);
    verify(mailer).send("owner@gamma.test", "Gamma", "gamma", TODAY.plusDays(1), 1);
  }

  @Test
  void skipsCandidateWhenSiblingWorkerAlreadyMarkedSent() {
    RenewalReminderRepository repo = Mockito.mock(RenewalReminderRepository.class);
    RenewalEmailSender mailer = Mockito.mock(RenewalEmailSender.class);

    ReminderCandidate cand =
        new ReminderCandidate(1L, "acme", "Acme", "ceo@acme.test", TODAY.plusDays(7));

    when(repo.findCandidates(TODAY.plusDays(14), 14)).thenReturn(List.of());
    when(repo.findCandidates(TODAY.plusDays(7), 7)).thenReturn(List.of(cand));
    when(repo.findCandidates(TODAY.plusDays(1), 1)).thenReturn(List.of());
    // Sibling beat us to the dedup INSERT.
    when(repo.markSent(eq(1L), eq(TODAY.plusDays(7)), eq(7))).thenReturn(false);

    RenewalReminderService service =
        new RenewalReminderService(repo, mailer, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(service.runOnce()).isZero();
    verify(mailer, never()).send(anyString(), anyString(), anyString(), any(), anyInt());
  }

  @Test
  void countsOnlyDeliveredEmails() {
    RenewalReminderRepository repo = Mockito.mock(RenewalReminderRepository.class);
    RenewalEmailSender mailer = Mockito.mock(RenewalEmailSender.class);

    ReminderCandidate ok =
        new ReminderCandidate(1L, "acme", "Acme", "ceo@acme.test", TODAY.plusDays(7));
    ReminderCandidate bounced =
        new ReminderCandidate(2L, "beta", "Beta", "bad@beta.test", TODAY.plusDays(7));

    when(repo.findCandidates(TODAY.plusDays(14), 14)).thenReturn(List.of());
    when(repo.findCandidates(TODAY.plusDays(7), 7)).thenReturn(List.of(ok, bounced));
    when(repo.findCandidates(TODAY.plusDays(1), 1)).thenReturn(List.of());
    when(repo.markSent(anyLong(), any(), anyInt())).thenReturn(true);
    when(mailer.send(eq("ceo@acme.test"), anyString(), anyString(), any(), anyInt()))
        .thenReturn(true);
    when(mailer.send(eq("bad@beta.test"), anyString(), anyString(), any(), anyInt()))
        .thenReturn(false);

    RenewalReminderService service =
        new RenewalReminderService(repo, mailer, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(service.runOnce()).isEqualTo(1);
    verify(mailer, times(2)).send(anyString(), anyString(), anyString(), any(), anyInt());
  }

  private static int anyInt() {
    return Mockito.anyInt();
  }
}
