package org.arguslog.worker.application.port;

import org.arguslog.worker.domain.Alert;
import org.arguslog.worker.domain.AlertDestination;

/**
 * One implementation per {@link AlertDestination.Kind}. Implementations declare which kind they
 * serve via {@link #kind()}. Failures are the impl's responsibility — log + drop, do not throw, do
 * not retry. P3 #5 will add throttling; persistent retry is intentionally deferred past P3.
 */
public interface AlertDispatcher {

  AlertDestination.Kind kind();

  void dispatch(Alert alert, AlertDestination destination);
}
