package com.github.wrossmorrow.extproc.conformance;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Multi-version Envoy target resolution for the conformance suite, mirroring the Go SDK's {@code
 * conformance/versions.go}: the supported Envoy release matrix, the default target, and the
 * endoflife.date based sync used to retire older releases.
 */
public class EnvoyVersions {

  /** Supported Envoy version. */
  public static final class EnvoyVersion {
    public final String tag;
    public final int major;
    public final int minor;

    public EnvoyVersion(String tag, int major, int minor) {
      this.tag = tag;
      this.major = major;
      this.minor = minor;
    }

    public String version() {
      return tag;
    }
  }

  /** Supported Envoy versions and their default target. */
  public static final class EnvoyVersionManifest {
    @JsonProperty("default")
    public String defaultVersion;

    public List<String> supported = new ArrayList<String>();
    public List<String> canary = new ArrayList<String>();

    public List<EnvoyVersion> versionsInOrder() {
      List<EnvoyVersion> result = new ArrayList<EnvoyVersion>();
      for (String tag : supported) {
        String trimmed = tag.startsWith("v") ? tag.substring(1) : tag;
        String cycle = trimmed;
        int dash = cycle.indexOf('-');
        if (dash >= 0) {
          cycle = cycle.substring(0, dash);
        }
        String[] parts = cycle.split("\\.");
        if (parts.length != 2) {
          continue;
        }
        try {
          result.add(new EnvoyVersion(tag, Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
        } catch (NumberFormatException e) {
          // skip malformed entries
        }
      }
      result.sort(
          Comparator.comparingInt((EnvoyVersion v) -> v.major)
              .thenComparingInt((EnvoyVersion v2) -> v2.minor));
      return result;
    }
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private EnvoyVersions() {}

  /** The checked-in {@code conformance/envoy-versions.json} manifest. */
  public static String loadEnvoyVersionsJson() {
    InputStream in = EnvoyVersions.class.getResourceAsStream("/envoy-versions.json");
    if (in == null) {
      throw new IllegalStateException("missing conformance resource: /envoy-versions.json");
    }
    try {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("cannot read /envoy-versions.json", e);
    } finally {
      try {
        in.close();
      } catch (Exception ignored) {
      }
    }
  }

  /** Load the {@code conformance/envoy-versions.json} manifest. */
  public static EnvoyVersionManifest load() {
    try {
      JsonNode root = MAPPER.readTree(loadEnvoyVersionsJson());
      EnvoyVersionManifest manifest = new EnvoyVersionManifest();
      manifest.defaultVersion = root.get("default").asText();
      for (JsonNode supported : root.get("supported")) {
        manifest.supported.add(supported.asText());
      }
      for (JsonNode canary : root.get("canary")) {
        manifest.canary.add(canary.asText());
      }
      return manifest;
    } catch (Exception e) {
      throw new IllegalStateException("cannot parse envoy-versions.json", e);
    }
  }

  /** Resolve the Envoy version to run: the {@code ENVOY_VERSION} override, else the default. */
  public static String resolve() {
    String override = System.getenv("ENVOY_VERSION");
    if (override != null && !override.isEmpty()) {
      return override;
    }
    return load().defaultVersion;
  }

  /**
   * Compute an {@link EnvoyVersionManifest} from an {@code endoflife.date} Envoy JSON payload,
   * retaining release cycles whose EOL has not yet passed as of {@code now}.
   */
  public static EnvoyVersionManifest syncFromEol(byte[] eolJson, Instant now, List<String> canary) {
    try {
      JsonNode root = MAPPER.readTree(new ByteArrayInputStream(eolJson));
      JsonNode entries = root.isArray() ? root : root.path("envoy");
      List<String> tags = new ArrayList<String>();
      for (JsonNode entry : entries) {
        if (!isCycleActive(entry.path("eol"), now)) {
          continue;
        }
        int[] numbers = parseCycle(entry.path("cycle").asText());
        if (numbers == null) {
          continue;
        }
        tags.add("v" + numbers[0] + "." + numbers[1] + "-latest");
      }
      List<String> supported = new ArrayList<String>();
      for (String tag : tags) {
        if (!supported.contains(tag)) {
          supported.add(tag);
        }
      }
      supported.sort(
          Comparator.comparingInt(EnvoyVersions::major).thenComparingInt(EnvoyVersions::minor));
      List<String> canaryCopy = new ArrayList<String>();
      if (canary != null && !canary.isEmpty()) {
        canaryCopy.addAll(canary);
      }
      if (canaryCopy.isEmpty()) {
        canaryCopy.add("dev");
      }
      EnvoyVersionManifest manifest = new EnvoyVersionManifest();
      if (!supported.isEmpty()) {
        manifest.defaultVersion = supported.get(supported.size() - 1);
      }
      manifest.supported = supported;
      manifest.canary = canaryCopy;
      return manifest;
    } catch (Exception e) {
      throw new IllegalStateException("cannot sync Envoy versions from endoflife.date", e);
    }
  }

  private static boolean isCycleActive(JsonNode eolNode, Instant now) {
    if (eolNode == null
        || eolNode.isNull()
        || eolNode.asText().isEmpty()
        || eolNode.asText().equals("false")) {
      return true;
    }
    if (eolNode.asText().equals("true")) {
      return false;
    }
    LocalDate eolDate = LocalDate.parse(eolNode.asText());
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    return eolDate.isAfter(today);
  }

  private static int[] parseCycle(String cycle) {
    String trimmed = cycle.startsWith("v") ? cycle.substring(1) : cycle;
    String[] parts = trimmed.split("\\.");
    if (parts.length != 2) {
      return null;
    }
    try {
      return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static int major(String tag) {
    String[] parts = tag.substring(1, tag.length() - "-latest".length()).split("\\.");
    return Integer.parseInt(parts[0]);
  }

  private static int minor(String tag) {
    String[] parts = tag.substring(1, tag.length() - "-latest".length()).split("\\.");
    return Integer.parseInt(parts[1]);
  }
}
