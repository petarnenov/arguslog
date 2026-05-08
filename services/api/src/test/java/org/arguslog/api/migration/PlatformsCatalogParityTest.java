package org.arguslog.api.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Catches the most common SDK release mistake: bumping a manifest version (package.json /
 * pyproject.toml) without bumping the matching row in {@code R__platforms_catalog.sql} (or
 * vice-versa). The catalog drives the project-create dropdown — drift here means the dashboard
 * advertises a stale SDK version to every new project.
 *
 * <p>Why a unit test and not a hook: the SDK manifests live in different sub-trees (packages/,
 * python-sdk/, java-sdk/) and each has its own release workflow. A single Java test reading the
 * monorepo gives one place to fail loudly when the catalog falls behind.
 */
class PlatformsCatalogParityTest {

  private static final Path REPO_ROOT = locateRepoRoot();

  private static final List<NodePackage> NODE_PACKAGES =
      List.of(
          new NodePackage("javascript", "packages/sdk-browser/package.json"),
          new NodePackage("react", "packages/sdk-react/package.json"),
          new NodePackage("angular", "packages/sdk-angular/package.json"),
          new NodePackage("vue", "packages/sdk-vue/package.json"),
          new NodePackage("nextjs", "packages/sdk-nextjs/package.json"),
          new NodePackage("react-native", "packages/sdk-react-native/package.json"),
          new NodePackage("node", "packages/sdk-node/package.json"));

  private static final String PYTHON_SLUG = "python";
  private static final Path PYTHON_PYPROJECT = REPO_ROOT.resolve("python-sdk/pyproject.toml");

  private static final String JAVA_SLUG = "java-spring";
  private static final Path JAVA_GRADLE_PROPERTIES =
      REPO_ROOT.resolve("java-sdk/gradle.properties");

  @Test
  void catalogVersionsMatchSdkManifests() throws IOException {
    Map<String, String> catalog = parseCatalogVersions();

    for (NodePackage pkg : NODE_PACKAGES) {
      String manifestVersion = readNodeVersion(REPO_ROOT.resolve(pkg.manifestPath));
      assertThat(catalog)
          .as(
              "platforms catalog (R__platforms_catalog.sql) row '%s' must match %s.version (%s)",
              pkg.slug, pkg.manifestPath, manifestVersion)
          .containsEntry(pkg.slug, manifestVersion);
    }

    String pythonVersion = readPythonVersion(PYTHON_PYPROJECT);
    assertThat(catalog)
        .as(
            "platforms catalog row 'python' must match python-sdk/pyproject.toml version (%s)",
            pythonVersion)
        .containsEntry(PYTHON_SLUG, pythonVersion);

    String javaVersion = readPropertiesValue(JAVA_GRADLE_PROPERTIES, "version");
    assertThat(catalog)
        .as(
            "platforms catalog row 'java-spring' must match java-sdk/gradle.properties:version (%s)",
            javaVersion)
        .containsEntry(JAVA_SLUG, javaVersion);

    // Sanity: every slug in the catalog must be covered by one of the manifests above. Stops a
    // newly-added platform from silently bypassing the parity check.
    var coveredSlugs = new HashSet<String>();
    NODE_PACKAGES.forEach(p -> coveredSlugs.add(p.slug));
    coveredSlugs.add(PYTHON_SLUG);
    coveredSlugs.add(JAVA_SLUG);
    assertThat(catalog.keySet())
        .as(
            "every slug in R__platforms_catalog.sql must be covered by an SDK manifest read by "
                + "this test (NODE_PACKAGES, PYTHON, or JAVA)")
        .isSubsetOf(coveredSlugs);
  }

  private static Map<String, String> parseCatalogVersions() throws IOException {
    Path migration =
        REPO_ROOT.resolve("services/api/src/main/resources/db/migration/R__platforms_catalog.sql");
    String sql = Files.readString(migration);
    // Match the per-row literal: ('slug', 'name', 'package', 'version', sort_order).
    // A relaxed-but-targeted regex is fine here: this test is the only consumer.
    Pattern row =
        Pattern.compile(
            "\\(\\s*'(?<slug>[^']+)'\\s*,\\s*'[^']+'\\s*,\\s*'[^']+'\\s*,\\s*'(?<version>[^']+)'\\s*,\\s*\\d+\\s*\\)");
    Matcher m = row.matcher(sql);
    Map<String, String> out = new LinkedHashMap<>();
    while (m.find()) {
      out.put(m.group("slug"), m.group("version"));
    }
    assertThat(out).as("regex against R__platforms_catalog.sql produced no rows").isNotEmpty();
    return out;
  }

  private static String readNodeVersion(Path packageJson) throws IOException {
    JsonNode root = new ObjectMapper().readTree(packageJson.toFile());
    String version = root.path("version").asText(null);
    assertThat(version).as("missing or empty .version in %s", packageJson).isNotBlank();
    return version;
  }

  private static String readPythonVersion(Path pyprojectToml) throws IOException {
    // pyproject.toml is small enough to parse with a regex; pulling in a TOML lib for one
    // line wouldn't pay for itself.
    String content = Files.readString(pyprojectToml);
    Matcher m = Pattern.compile("(?m)^\\s*version\\s*=\\s*\"([^\"]+)\"").matcher(content);
    assertThat(m.find())
        .as("could not find a top-level version = \"...\" line in %s", pyprojectToml)
        .isTrue();
    return m.group(1);
  }

  private static String readPropertiesValue(Path propertiesFile, String key) throws IOException {
    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(propertiesFile)) {
      props.load(in);
    }
    String value = props.getProperty(key);
    assertThat(value).as("missing or empty key '%s' in %s", key, propertiesFile).isNotBlank();
    return value;
  }

  private static Path locateRepoRoot() {
    // Walk upward until we hit the directory holding settings.gradle.kts. Tests can run from
    // either the project root (./gradlew) or the module dir (IDE), so we don't hard-code.
    Path cursor = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8; i++) {
      if (Files.exists(cursor.resolve("settings.gradle.kts"))) {
        return cursor;
      }
      Path parent = cursor.getParent();
      if (parent == null) break;
      cursor = parent;
    }
    throw new IllegalStateException("Cannot locate repo root from " + Path.of("").toAbsolutePath());
  }

  private record NodePackage(String slug, String manifestPath) {}
}
