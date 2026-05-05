package org.arguslog.worker.application.port;

/**
 * Per-rule "did I just fire this?" gate. Atomic test-and-set: a {@code true} return both means
 * "please fire" AND grants the caller the throttling lock for {@code throttleSeconds}; a {@code
 * false} return means "another evaluator just fired this rule, stay quiet".
 *
 * <p>Failure policy on the storage backend (Redis): err on the side of firing. A noisy outage is
 * observable; silent alerts during an incident are worse.
 */
public interface RuleThrottle {

  boolean tryFire(long ruleId, int throttleSeconds);
}
