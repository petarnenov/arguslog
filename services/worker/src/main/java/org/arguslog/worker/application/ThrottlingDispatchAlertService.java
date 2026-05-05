package org.arguslog.worker.application;

import org.arguslog.worker.application.port.RuleThrottle;
import org.arguslog.worker.domain.AlertRule;
import org.arguslog.worker.domain.PersistedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Decorator over {@link DispatchAlertService} that drops calls within a rule's cooldown window.
 * {@code @Primary} so {@link EvaluatePersistedEventService} (which depends on {@link
 * DispatchAlertUseCase}) gets the throttled view; the inner service is still injectable by concrete
 * type for tests that want to bypass the gate.
 */
@Service
@Primary
public class ThrottlingDispatchAlertService implements DispatchAlertUseCase {

  private static final Logger log = LoggerFactory.getLogger(ThrottlingDispatchAlertService.class);

  private final DispatchAlertService delegate;
  private final RuleThrottle throttle;

  public ThrottlingDispatchAlertService(DispatchAlertService delegate, RuleThrottle throttle) {
    this.delegate = delegate;
    this.throttle = throttle;
  }

  @Override
  public int dispatch(AlertRule rule, PersistedEvent event) {
    if (!throttle.tryFire(rule.id(), rule.throttleSeconds())) {
      log.debug(
          "rule {} throttled (within {}s); skipping dispatch for issue {}",
          rule.id(),
          rule.throttleSeconds(),
          event.issueId());
      return 0;
    }
    return delegate.dispatch(rule, event);
  }
}
