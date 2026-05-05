package org.arguslog.worker.application;

import java.util.ArrayList;
import java.util.List;
import org.arguslog.worker.application.port.AlertRuleRepository;
import org.arguslog.worker.domain.AlertRule;
import org.arguslog.worker.domain.PersistedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluatePersistedEventService implements EvaluatePersistedEventUseCase {

  private static final Logger log = LoggerFactory.getLogger(EvaluatePersistedEventService.class);

  private final AlertRuleRepository rules;
  private final RuleEvaluator evaluator;
  private final DispatchAlertUseCase dispatcher;

  public EvaluatePersistedEventService(
      AlertRuleRepository rules, RuleEvaluator evaluator, DispatchAlertUseCase dispatcher) {
    this.rules = rules;
    this.evaluator = evaluator;
    this.dispatcher = dispatcher;
  }

  @Override
  @Transactional(readOnly = true)
  public List<AlertRule> evaluate(PersistedEvent event) {
    List<AlertRule> candidates = rules.enabledForProject(event.projectId());
    if (candidates.isEmpty()) return List.of();

    List<AlertRule> matches = new ArrayList<>(candidates.size());
    for (AlertRule rule : candidates) {
      if (evaluator.matches(rule, event)) {
        matches.add(rule);
      }
    }
    for (AlertRule m : matches) {
      // Single info line per fired rule keeps audit trails greppable; the dispatcher logs its own
      // per-destination outcome at warn (only on failure).
      log.info(
          "alert rule matched: ruleId={} projectId={} issueId={} occurrence={}",
          m.id(),
          event.projectId(),
          event.issueId(),
          event.occurrenceCount());
      try {
        dispatcher.dispatch(m, event);
      } catch (RuntimeException e) {
        // Last-line defense: dispatch should already isolate per-destination errors. If something
        // upstream of the per-destination loop blows up (e.g. resolver), we still want the next
        // matched rule on this event to get its shot.
        log.warn(
            "dispatch crashed for ruleId={} issueId={}: {}",
            m.id(),
            event.issueId(),
            e.getMessage());
      }
    }
    return matches;
  }
}
