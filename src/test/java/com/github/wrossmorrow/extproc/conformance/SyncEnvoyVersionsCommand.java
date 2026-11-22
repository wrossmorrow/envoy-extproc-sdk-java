package com.github.wrossmorrow.extproc.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wrossmorrow.extproc.conformance.EnvoyVersions.EnvoyVersionManifest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Command-line entry point for syncing the Envoy version manifest from endoflife.date. */
public class SyncEnvoyVersionsCommand {

  public static void main(String[] args) throws Exception {
    String eolJsonPath = args.length > 0 ? args[0] : "/tmp/eol-envoy.json";
    String outputPath = args.length > 1 ? args[1] : "/work/repo/conformance/envoy-versions.json";
    List<String> canary =
        Arrays.asList(args.length > 2 ? args[2].split(",") : new String[] {"dev"});

    byte[] eolJson = Files.readAllBytes(Paths.get(eolJsonPath));
    EnvoyVersionManifest manifest = EnvoyVersions.syncFromEol(eolJson, Instant.now(), canary);
    ObjectMapper mapper = new ObjectMapper();
    String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
    Path manifestPath = Paths.get(outputPath).toAbsolutePath().normalize();
    Files.write(manifestPath, json.getBytes(StandardCharsets.UTF_8));
    System.out.println("wrote " + outputPath);

    Path manifestDir = manifestPath.getParent();
    Path repoRoot = manifestDir != null ? manifestDir.getParent() : null;
    if (repoRoot != null) {
      updateCompose(repoRoot.resolve("docker-compose.yaml"), manifest);
      updateConformanceDoc(repoRoot.resolve("CONFORMANCE.md"), manifest);
      updateJustfile(repoRoot.resolve("justfile"), manifest);
    }
  }

  private static void updateCompose(Path path, EnvoyVersionManifest manifest) throws Exception {
    if (!Files.exists(path)) {
      return;
    }
    String original = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    String updated = EnvoyVersions.syncDockerComposeYaml(original, manifest);
    writeIfChanged(path, original, updated);
  }

  private static void updateConformanceDoc(Path path, EnvoyVersionManifest manifest)
      throws Exception {
    if (!Files.exists(path)) {
      return;
    }
    String original = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    String updated = EnvoyVersions.syncConformanceDoc(original, manifest);
    writeIfChanged(path, original, updated);
  }

  private static void updateJustfile(Path path, EnvoyVersionManifest manifest) throws Exception {
    if (!Files.exists(path)) {
      return;
    }
    String original = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    String updated = EnvoyVersions.syncJustfile(original, manifest);
    writeIfChanged(path, original, updated);
  }

  private static void writeIfChanged(Path path, String original, String updated) throws Exception {
    if (!original.equals(updated)) {
      Files.write(path, updated.getBytes(StandardCharsets.UTF_8));
      System.out.println("updated " + path.getFileName());
    }
  }
}
