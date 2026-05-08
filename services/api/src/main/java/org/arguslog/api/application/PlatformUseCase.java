package org.arguslog.api.application;

import java.util.List;
import org.arguslog.api.domain.Platform;

public interface PlatformUseCase {

  /** All currently-supported SDKs in the order the dashboard should display them. */
  List<Platform> list();
}
