package org.arguslog.worker.application.port;

import java.util.Optional;

/** Outbound port for fetching raw {@code .map} bytes from object storage. */
public interface SourceMapStore {

  Optional<String> fetch(String r2Key);
}
