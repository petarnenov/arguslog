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

  public EvaluatePersistedEventService(AlertRuleRepository rules, RuleEvaluator evaluator) {
    this.rules = rules;
    this.evaluator = evaluator;
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
    if (!matches.isEmpty()) {
      // Until P3 #4 wires the dispatcher this is the only signal a rule is firing — keep it loud
      // enough to debug without overwhelming logs (one line per match, not per event).
      for (AlertRule m : matches) {
        log.info(
            "alert rule matched: ruleId={} projectId={} issueId={} occurrence={}",
            m.id(),
            event.projectId(),
            event.issueId(),
            event.occurrenceCount());
      }
    }
    return matches;
  }
}
