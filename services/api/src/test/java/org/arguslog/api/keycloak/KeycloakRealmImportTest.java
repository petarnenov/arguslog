package org.arguslog.api.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Locks the {@code services/keycloak/realm/arguslog-realm.json} export as a contract.
 *
 * <p>Two layers of validation:
 *
 * <ol>
 *   <li><b>Static JSON checks</b> — enforce the structural invariants the rest of the system relies
 *       on (PKCE on arguslog-web, bearer-only arguslog-api, demo user pre-verified, etc.). Fast and
 *       runs always.
 *   <li><b>Live import smoke</b> — boots a real Keycloak with the realm imported and hits {@code
 *       /.well-known/openid-configuration}. Catches "Keycloak refused the import" regressions that
 *       JSON shape can't see (e.g. references to nonexistent roles).
 * </ol>
 *
 * <p>Live token issuance + admin-API assertions need bootstrap-admin auth, which is brittle across
 * Keycloak 25's KC_BOOTSTRAP_ADMIN_* migration; deferred to the JWT-aware integration test that
 * lands when api gains its first JWT-secured endpoint.
 */
class KeycloakRealmImportTest {

  private static final ObjectMapper mapper = new ObjectMapper();
  private static final HttpClient http = HttpClient.newHttpClient();
  private static JsonNode realmJson;

  private static KeycloakContainer keycloak;

  @BeforeAll
  static void readRealm() throws Exception {
    realmJson = mapper.readTree(Files.readString(resolveRealmJson()));
  }

  @AfterAll
  static void stopContainer() {
    if (keycloak != null) keycloak.stop();
  }

  // ── static JSON contract ────────────────────────────────────────────────

  @Test
  void realmHasStableIdAndDevSettings() {
    assertThat(realmJson.path("realm").asText()).isEqualTo("arguslog");
    assertThat(realmJson.path("enabled").asBoolean()).isTrue();
    assertThat(realmJson.path("registrationAllowed").asBoolean()).isTrue();
    assertThat(realmJson.path("loginWithEmailAllowed").asBoolean()).isTrue();
    assertThat(realmJson.path("resetPasswordAllowed").asBoolean()).isTrue();
  }

  @Test
  void arguslogWebClientIsPublicPkceWithLocalDevRedirects() {
    JsonNode client = findClient("arguslog-web");
    assertThat(client.path("publicClient").asBoolean()).isTrue();
    assertThat(client.path("standardFlowEnabled").asBoolean()).isTrue();
    // PKCE pin: prevents the auth code being usable without the verifier.
    assertThat(client.path("attributes").path("pkce.code.challenge.method").asText())
        .isEqualTo("S256");
    assertThat(asTextList(client.path("redirectUris"))).contains("http://localhost:5173/*");
    assertThat(asTextList(client.path("webOrigins"))).contains("http://localhost:5173");
  }

  @Test
  void arguslogApiClientIsBearerOnlyWithNoFlows() {
    JsonNode client = findClient("arguslog-api");
    assertThat(client.path("bearerOnly").asBoolean()).isTrue();
    assertThat(client.path("publicClient").asBoolean()).isFalse();
    assertThat(client.path("standardFlowEnabled").asBoolean()).isFalse();
    assertThat(client.path("directAccessGrantsEnabled").asBoolean()).isFalse();
  }

  @Test
  void realmShipsWithoutSeedUsers() {
    // Production realm import must not seed demo / staff accounts — the platform expects real
    // users to register through the Keycloak signup flow. Removed in the P5 launch hygiene pass
    // after the original demo user (demo@arguslog.local / demo) was deleted from production.
    JsonNode users = realmJson.path("users");
    assertThat(users.isMissingNode() || (users.isArray() && users.size() == 0)).isTrue();
  }

  @Test
  void realmRolesIncludeUserAndStaff() {
    List<String> roles = asTextList(realmJson.path("roles").path("realm"), "name");
    assertThat(roles).contains("arguslog:user", "arguslog:staff");
  }

  // ── live import smoke ───────────────────────────────────────────────────

  @Test
  void keycloakAcceptsTheImportAndExposesTheExpectedDiscoveryShape() throws Exception {
    keycloak =
        new KeycloakContainer("quay.io/keycloak/keycloak:25.0")
            .withRealmImportFile("arguslog-realm.json");
    keycloak.start();

    String baseUrl = keycloak.getAuthServerUrl();
    JsonNode discovery = getJson(baseUrl + "/realms/arguslog/.well-known/openid-configuration");

    assertThat(discovery.path("issuer").asText()).isEqualTo(baseUrl + "/realms/arguslog");
    assertThat(discovery.path("token_endpoint").asText())
        .startsWith(baseUrl + "/realms/arguslog/protocol/openid-connect/token");
    assertThat(discovery.path("jwks_uri").asText()).contains("/protocol/openid-connect/certs");
    // PKCE must be advertised — the browser SDK depends on it.
    assertThat(discovery.path("code_challenge_methods_supported").toString()).contains("S256");
    assertThat(asTextList(discovery.path("response_types_supported"))).contains("code");
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static JsonNode findClient(String clientId) {
    JsonNode clients = realmJson.path("clients");
    assertThat(clients.isArray()).isTrue();
    for (JsonNode client : clients) {
      if (clientId.equals(client.path("clientId").asText())) {
        return client;
      }
    }
    throw new AssertionError("client not found in realm: " + clientId);
  }

  private static List<String> asTextList(JsonNode array) {
    List<String> out = new java.util.ArrayList<>();
    if (!array.isArray()) return out;
    array.forEach(n -> out.add(n.asText()));
    return out;
  }

  private static List<String> asTextList(JsonNode array, String field) {
    List<String> out = new java.util.ArrayList<>();
    if (!array.isArray()) return out;
    array.forEach(n -> out.add(n.path(field).asText()));
    return out;
  }

  private static JsonNode getJson(String url) throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").GET().build();
    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "HTTP " + res.statusCode() + " for " + url + ": " + res.body());
    }
    return mapper.readTree(res.body());
  }

  private static Path resolveRealmJson() {
    // The Gradle build copies services/keycloak/realm/arguslog-realm.json into
    // build/resources/test as a `processTestResources` step (see services/api/build.gradle.kts),
    // so the canonical lookup goes through the classpath. Filesystem-relative paths broke on
    // CI runners where the JVM cwd differs from a developer's "run from services/api" flow.
    URL resource = KeycloakRealmImportTest.class.getResource("/arguslog-realm.json");
    if (resource == null) {
      throw new IllegalStateException(
          "Cannot locate /arguslog-realm.json on the test classpath. Verify "
              + "services/api/build.gradle.kts copies it via processTestResources.");
    }
    try {
      return Path.of(resource.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException("arguslog-realm.json URL is not a valid URI", e);
    }
  }
}
