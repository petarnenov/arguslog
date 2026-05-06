package org.arguslog.api.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stripe.StripeClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.arguslog.api.alerts.application.port.AlertDestinationRepository;
import org.arguslog.api.alerts.application.port.AlertRuleRepository;
import org.arguslog.api.application.port.DsnRepository;
import org.arguslog.api.application.port.EventRepository;
import org.arguslog.api.application.port.IssueRepository;
import org.arguslog.api.application.port.MembershipRepository;
import org.arguslog.api.application.port.OrgWriteRepository;
import org.arguslog.api.application.port.ProjectRepository;
import org.arguslog.api.application.port.ProjectWriteRepository;
import org.arguslog.api.application.port.UserRepository;
import org.arguslog.api.auth.application.port.PatRepository;
import org.arguslog.api.auth.application.port.TokenHasher;
import org.arguslog.api.billing.application.PortalUseCase;
import org.arguslog.api.billing.application.port.BillingCustomerRepository;
import org.arguslog.api.billing.application.port.OrgPlanRepository;
import org.arguslog.api.billing.application.port.UsageRepository;
import org.arguslog.api.releases.application.port.ReleaseRepository;
import org.arguslog.api.releases.application.port.SourceMapArtifactRepository;
import org.arguslog.api.releases.application.port.SourceMapStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Generates the api's OpenAPI spec by hitting {@code /v3/api-docs} on a context-loaded test server,
 * writes it (pretty-printed, key-sorted, deterministic) to {@code services/api/openapi.json}, and
 * asserts the file is byte-identical to what's committed.
 *
 * <p>The committed snapshot is the contract: any endpoint shape change without an updated {@code
 * openapi.json} fails CI. To accept a change, run {@code ./gradlew :services:api:test --tests
 * OpenApiContractTest} locally and commit the diff.
 *
 * <p>Same shape as the Pact consumer test (sdk-browser → ingest): regenerate-and-diff is the
 * lightest contract pattern that catches accidental breakage in PR review instead of in production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration"
    })
class OpenApiContractTest {

  @Autowired MockMvc mvc;

  // Ports mocked so the JDBC adapters don't try to wire a DataSource — we only need the
  // springdoc machinery + controllers loaded to enumerate endpoints.
  @MockitoBean IssueRepository issueRepository;
  @MockitoBean EventRepository eventRepository;
  @MockitoBean ProjectRepository projectRepository;
  @MockitoBean MembershipRepository membershipRepository;
  @MockitoBean AlertDestinationRepository alertDestinationRepository;
  @MockitoBean AlertRuleRepository alertRuleRepository;
  @MockitoBean OrgWriteRepository orgWriteRepository;
  @MockitoBean UserRepository userRepository;
  @MockitoBean ProjectWriteRepository projectWriteRepository;
  @MockitoBean DsnRepository dsnRepository;
  @MockitoBean ReleaseRepository releaseRepository;
  @MockitoBean SourceMapArtifactRepository sourceMapArtifactRepository;
  @MockitoBean SourceMapStorage sourceMapStorage;
  @MockitoBean PatRepository patRepository;
  @MockitoBean TokenHasher tokenHasher;
  @MockitoBean UsageRepository usageRepository;
  @MockitoBean OrgPlanRepository orgPlanRepository;
  @MockitoBean BillingCustomerRepository billingCustomerRepository;
  @MockitoBean PortalUseCase portalUseCase;
  @MockitoBean StripeClient stripeClient;

  @Test
  void generatedSpecMatchesTheCommittedSnapshot() throws Exception {
    MvcResult result =
        mvc.perform(MockMvcRequestBuilders.get("/v3/api-docs").accept("application/json"))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);

    ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    JsonNode spec = mapper.readTree(result.getResponse().getContentAsByteArray());

    // Strip Springdoc's per-boot non-deterministic fields (none today, but pin the shape).
    String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec) + "\n";

    Path snapshot = resolveSnapshotPath();
    if (Boolean.parseBoolean(System.getenv().getOrDefault("ARGUS_OPENAPI_WRITE", "false"))) {
      Files.writeString(
          snapshot, pretty, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
      return;
    }

    String committed = Files.exists(snapshot) ? Files.readString(snapshot) : "";
    if (!pretty.equals(committed)) {
      // Write the would-be snapshot to build/ so the failing CI run uploads it as an artifact
      // and a human can `cp` it over the committed copy when the change is intentional.
      Path drift = Path.of("build", "openapi", "openapi.json").toAbsolutePath();
      Files.createDirectories(drift.getParent());
      Files.writeString(
          drift, pretty, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
      throw new AssertionError(
          "OpenAPI spec drifted from "
              + snapshot
              + ".\n"
              + "Generated copy written to "
              + drift
              + ".\n"
              + "If the change is intentional, regenerate with: "
              + "ARGUS_OPENAPI_WRITE=true ./gradlew :services:api:test "
              + "--tests org.arguslog.api.openapi.OpenApiContractTest");
    }
  }

  private static Path resolveSnapshotPath() {
    // Tests run from services/api/ under Gradle; from the repo root in some IDE configs.
    Path[] candidates = {
      Path.of("openapi.json").toAbsolutePath(),
      Path.of("services", "api", "openapi.json").toAbsolutePath(),
    };
    for (Path p : candidates) {
      if (Files.exists(p.getParent())) return p;
    }
    return candidates[0];
  }
}
