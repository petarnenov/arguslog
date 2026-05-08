package org.arguslog.api.application;

import java.util.List;
import org.arguslog.api.application.port.PlatformRepository;
import org.arguslog.api.domain.Platform;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformService implements PlatformUseCase {

  private final PlatformRepository platforms;

  public PlatformService(PlatformRepository platforms) {
    this.platforms = platforms;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Platform> list() {
    return platforms.listEnabled();
  }
}
