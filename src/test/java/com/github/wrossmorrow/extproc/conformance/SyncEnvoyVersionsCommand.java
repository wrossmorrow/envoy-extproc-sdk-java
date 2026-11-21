package com.github.wrossmorrow.extproc.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wrossmorrow.extproc.conformance.EnvoyVersions.EnvoyVersionManifest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    Files.write(Paths.get(outputPath), json.getBytes(StandardCharsets.UTF_8));
    System.out.println("wrote " + outputPath);
  }
}
