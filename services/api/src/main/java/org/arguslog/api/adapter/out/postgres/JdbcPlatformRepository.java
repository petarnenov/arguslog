package org.arguslog.api.adapter.out.postgres;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.arguslog.api.application.port.PlatformRepository;
import org.arguslog.api.domain.Platform;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPlatformRepository implements PlatformRepository {

  private final JdbcTemplate jdbc;

  public JdbcPlatformRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public List<Platform> listEnabled() {
    return jdbc.query(
        """
        SELECT slug, name, sdk_package, sdk_version, sort_order
          FROM platforms
         WHERE enabled
         ORDER BY sort_order ASC, slug ASC
        """,
        (rs, rowNum) ->
            new Platform(
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("sdk_package"),
                rs.getString("sdk_version"),
                rs.getInt("sort_order")));
  }

  @Override
  public Set<String> enabledSlugs() {
    return listEnabled().stream().map(Platform::slug).collect(Collectors.toUnmodifiableSet());
  }
}
