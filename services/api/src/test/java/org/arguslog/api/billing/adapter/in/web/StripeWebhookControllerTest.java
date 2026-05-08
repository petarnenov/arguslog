package org.arguslog.api.billing.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import org.arguslog.api.billing.application.StripeWebhookUseCase.Outcome;
import org.arguslog.api.testsupport.AbstractControllerTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

// Webhook controller short-circuits with 503 when arguslog.stripe.webhook-secret is blank — give
// it a value so the verifier-rejection / handler-success tests can exercise the real branches.
// @TestPropertySource on the subclass merges with the base class's autoconfigure exclusion list.
@TestPropertySource(properties = "arguslog.stripe.webhook-secret=whsec_test_4_unit")
class StripeWebhookControllerTest extends AbstractControllerTest {

  @Test
  void missingSignatureReturns400ProblemJson() throws Exception {
    mvc.perform(
            post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"evt_x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Missing signature"))
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Signature")));
    verify(stripeWebhookUseCase, never()).handle(any());
  }

  @Test
  void invalidSignatureReturns400ProblemJson() throws Exception {
    when(stripeEventVerifier.verify(
            eq("{\"id\":\"evt_x\"}"), eq("t=1,v1=bad"), eq("whsec_test_4_unit")))
        .thenThrow(new SignatureVerificationException("bad sig", "t=1,v1=bad"));

    mvc.perform(
            post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=1,v1=bad")
                .content("{\"id\":\"evt_x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid signature"));
    verify(stripeWebhookUseCase, never()).handle(any());
  }

  @Test
  void verifiedEventDelegatesToUseCaseAndReturnsOutcome() throws Exception {
    Event event = org.mockito.Mockito.mock(Event.class);
    when(stripeEventVerifier.verify(any(), any(), eq("whsec_test_4_unit"))).thenReturn(event);
    when(stripeWebhookUseCase.handle(event)).thenReturn(Outcome.PROCESSED);

    mvc.perform(
            post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=1,v1=ok")
                .content("{\"id\":\"evt_y\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("processed"));
  }

  @Test
  void duplicateEventOutcomeStillReturns200() throws Exception {
    Event event = org.mockito.Mockito.mock(Event.class);
    when(stripeEventVerifier.verify(any(), any(), any())).thenReturn(event);
    when(stripeWebhookUseCase.handle(event)).thenReturn(Outcome.ALREADY_SEEN);

    mvc.perform(
            post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=1,v1=ok")
                .content("{\"id\":\"evt_dup\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("already_seen"));
  }

  @Test
  void handlerCrashYields500ProblemJsonSoStripeRedelivers() throws Exception {
    Event event = org.mockito.Mockito.mock(Event.class);
    when(stripeEventVerifier.verify(any(), any(), any())).thenReturn(event);
    when(stripeWebhookUseCase.handle(event)).thenThrow(new RuntimeException("db down"));

    mvc.perform(
            post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=1,v1=ok")
                .content("{\"id\":\"evt_z\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Webhook handler failed"));
  }
}
