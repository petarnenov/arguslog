package dev.argus.api.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.List;

/**
 * Standard list-endpoint envelope. The {@code page.next} field is a cursor the client passes back
 * via {@code ?cursor=}; it is omitted on the last page.
 */
public record PageResponse<T>(List<T> data, PageMeta page) {

  public record PageMeta(@JsonInclude(Include.NON_NULL) String next) {}

  public static <T> PageResponse<T> of(List<T> data, String nextCursor) {
    return new PageResponse<>(data, new PageMeta(nextCursor));
  }
}
