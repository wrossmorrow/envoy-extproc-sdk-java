package com.github.wrossmorrow.extproc.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wrossmorrow.extproc.conformance.EnvoyVersions.EnvoyVersionManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Generates the Envoy conformance markdown matrix and JSON summary artifacts. */
public class ConformanceReport {

  public static final String CONFORMANCE_MATRIX_START_MARKER =
      "<!-- BEGIN GENERATED CONFORMANCE MATRIX -->";
  public static final String CONFORMANCE_MATRIX_END_MARKER =
      "<!-- END GENERATED CONFORMANCE MATRIX -->";

  public static final class FeatureSpec {
    public String id;
    public String category;
    public String title;
    public String sdkStatus;
    public String sdkSurface;
    public String notes;
    public List<String> tests = new ArrayList<String>();
  }

  public static final class CellSummary {
    public String status = "not_run";
    public String display = "not run";
    public int passed;
    public int failed;
    public int skipped;
    public int missing;
    public int total;
  }

  public static final class FeatureSummary {
    public String id;
    public String category;
    public String title;
    public String sdkStatus;
    public String sdkSurface;
    public String notes;
    public List<String> tests = new ArrayList<String>();
    public Map<String, CellSummary> cells = new TreeMap<String, CellSummary>();
  }

  public static final class VersionTestRun {
    public String version;
    public Map<String, String> outcomes = new LinkedHashMap<String, String>();
  }

  public static final class VersionResult {
    public String version;
    public VersionTestRun run;
  }

  public static final class SummaryReport {
    public String generatedAt;
    public List<String> versions = new ArrayList<String>();
    public List<FeatureSummary> features = new ArrayList<FeatureSummary>();
  }

  public static SummaryReport buildReport(
      List<String> versions, List<FeatureSpec> features, Map<String, VersionTestRun> runs) {
    SummaryReport report = new SummaryReport();
    report.versions = new ArrayList<String>();
    for (String version : versions) {
      report.versions.add(version);
    }
    for (FeatureSpec feature : features) {
      FeatureSummary summary = new FeatureSummary();
      summary.id = feature.id;
      summary.category = feature.category;
      summary.title = feature.title;
      summary.sdkStatus = feature.sdkStatus;
      summary.sdkSurface = feature.sdkSurface;
      summary.notes = feature.notes;
      summary.tests = new ArrayList<String>();
      if (feature.tests != null) {
        summary.tests.addAll(feature.tests);
      }
      summary.cells = new TreeMap<String, CellSummary>();
      for (String version : versions) {
        CellSummary cell = new CellSummary();
        cell.status = cellStatusFor(feature, version, runs);
        cell.display = cell.status.replace('_', ' ');
        cell.total = 1;
        switch (cell.status) {
          case "pass":
            cell.passed = 1;
            break;
          case "fail":
            cell.failed = 1;
            break;
          case "partial":
            cell.failed = 1;
            cell.missing = 1;
            break;
          case "known_gap":
          case "unsupported":
            break;
          default:
            break;
        }
        summary.cells.put(version, cell);
      }
      report.features.add(summary);
    }
    report.generatedAt = java.time.Instant.now().toString();
    return report;
  }

  public static String cellStatusFor(
      FeatureSpec feature, String version, Map<String, VersionTestRun> runs) {
    VersionTestRun run = runs.get(version);
    if (run == null) {
      return "not_run";
    }
    String outcome = run.outcomes.getOrDefault("TestEnvoyConformance/" + feature.id, "not_run");
    if ("pass".equalsIgnoreCase(outcome)) {
      return "pass";
    }
    if ("fail".equalsIgnoreCase(outcome)) {
      return "fail";
    }
    if ("skip".equalsIgnoreCase(outcome)) {
      return "skipped";
    }
    if ("error".equalsIgnoreCase(outcome)) {
      return "fail";
    }
    if (outcome != null && !outcome.isEmpty() && !"not_run".equalsIgnoreCase(outcome)) {
      // unknown outcome vocabulary entries surface as gaps rather than failures
      return "known_gap";
    }
    return "not_run";
  }

  public static String renderMarkdown(SummaryReport report) {
    StringBuilder sb = new StringBuilder();
    sb.append(CONFORMANCE_MATRIX_START_MARKER).append('\n');
    sb.append(CONFORMANCE_MATRIX_END_MARKER).append('\n');
    sb.append("# Envoy conformance matrix\n\n");
    sb.append("Outcomes are generated from Envoy conformance runs; see `CONFORMANCE.md`.\n\n");
    sb.append("| Feature | Category | SDK status");
    for (String version : report.versions) {
      sb.append(" | ").append(version);
    }
    sb.append(" |\n");
    sb.append("| --- | --- | --- |");
    for (String version : report.versions) {
      sb.append(" --- |");
    }
    sb.append(" |\n");
    for (FeatureSummary feature : report.features) {
      StringBuilder row = new StringBuilder(64);
      row.append("| ")
          .append(feature.id)
          .append(" | ")
          .append(feature.category)
          .append(" | ")
          .append(feature.sdkStatus);
      for (String version : report.versions) {
        CellSummary cell = feature.cells.get(version);
        row.append(" | ").append(cell == null ? "not_run" : cell.status);
      }
      row.append(" |\n");
      sb.append(row);
    }
    sb.append("\n");
    return sb.toString();
  }

  public static String renderStepSummary(SummaryReport report) {
    StringBuilder sb = new StringBuilder();
    sb.append("### Envoy conformance\n\n");
    sb.append("| Feature | Status |\n");
    sb.append("| --- | --- |\n");
    for (FeatureSummary feature : report.features) {
      for (Map.Entry<String, CellSummary> entry : feature.cells.entrySet()) {
        CellSummary cell = entry.getValue();
        sb.append("| ").append(feature.id).append(" | ").append(cell.status).append(" |\n");
      }
    }
    return sb.toString();
  }

  public static void writeMarkdown(String markdownOut, SummaryReport report) throws IOException {
    String markdown = renderMarkdown(report);
    Files.write(Paths.get(markdownOut), markdown.getBytes(StandardCharsets.UTF_8));
  }

  public static void writeJson(SummaryReport report, Path jsonOut) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    mapper.writerWithDefaultPrettyPrinter().writeValue(Files.newOutputStream(jsonOut), report);
  }

  public static void updateConformanceDoc(
      String docPath, SummaryReport report, List<String> versions) throws IOException {
    String markdown = renderMarkdown(report);
    String content = new String(Files.readAllBytes(Paths.get(docPath)), StandardCharsets.UTF_8);
    String markerStart = CONFORMANCE_MATRIX_START_MARKER;
    String markerEnd = CONFORMANCE_MATRIX_END_MARKER;
    String rebuilt = content;
    int startIndex = rebuilt.indexOf(markerStart);
    int endIndex = rebuilt.indexOf(markerEnd);
    StringBuilder updated = new StringBuilder();
    if (startIndex < 0 || endIndex < 0) {
      // fall back to appending the generated block at the end of the doc
      updated.append(content);
      updated.append("\n");
      updated.append(markdown);
      updated.append("\n");
    } else {
      updated.append(rebuilt.substring(0, startIndex));
      updated.append(markdown);
      updated.append(rebuilt.substring(endIndex + markerEnd.length()));
    }
    Files.write(Paths.get(docPath), updated.toString().getBytes(StandardCharsets.UTF_8));
  }

  public static void validateStrict(SummaryReport report, List<String> versions) throws Exception {
    for (FeatureSummary feature : report.features) {
      CellSummary defaultCell = feature.cells.get(versions.get(0));
      if (defaultCell == null) {
        throw new IllegalStateException("missing default version cell for feature " + feature.id);
      }
      if ("fail".equals(defaultCell.status)) {
        throw new IllegalStateException(
            "default version " + versions.get(0) + " has failing feature " + feature.id);
      }
    }
  }

  public static List<FeatureSpec> loadFeatures(Path featuresPath) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    mapper.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    // YAML features are loaded through the YAML factory
    com.fasterxml.jackson.dataformat.yaml.YAMLFactory yamlFactory =
        new com.fasterxml.jackson.dataformat.yaml.YAMLFactory();
    ObjectMapper yamlMapper = new ObjectMapper(yamlFactory);
    yamlMapper.enable(
        com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    FeatureDocument document =
        yamlMapper.readValue(Files.newInputStream(featuresPath), FeatureDocument.class);
    List<FeatureSpec> features = new ArrayList<FeatureSpec>();
    for (FeatureYamlFeature raw : document.features) {
      FeatureSpec feature = new FeatureSpec();
      feature.id = raw.id;
      feature.category = raw.category;
      feature.title = raw.title;
      feature.sdkStatus = raw.sdkStatus;
      feature.sdkSurface = raw.sdkSurface;
      feature.notes = raw.notes;
      feature.tests = new ArrayList<String>();
      if (raw.tests != null) {
        feature.tests.addAll(raw.tests);
      }
      features.add(feature);
    }
    return features;
  }

  public static final class FeatureYamlFeature {
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public String id;

    public String category;
    public String title;

    @com.fasterxml.jackson.annotation.JsonAlias("sdk_status")
    public String sdkStatus;

    public String sdkSurface;
    public String notes;
    public List<String> tests = new ArrayList<String>();
  }

  public static final class FeatureDocument {
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public List<FeatureYamlFeature> features = new ArrayList<FeatureYamlFeature>();
  }

  public static Map<String, VersionTestRun> loadOutcomes(Path resultsDir) throws IOException {
    Map<String, VersionTestRun> runs = new LinkedHashMap<String, VersionTestRun>();
    if (!Files.isDirectory(resultsDir)) {
      return runs;
    }
    List<Path> files = new ArrayList<Path>();
    for (Path p : Files.newDirectoryStream(resultsDir, "results-*.json")) {
      files.add(p);
    }
    Collections.sort(files);
    for (Path p : files) {
      VersionResult result = new ObjectMapper().readValue(p.toFile(), VersionResult.class);
      VersionTestRun run = new VersionTestRun();
      run.version = result.version;
      run.outcomes = result.run == null ? new LinkedHashMap<String, String>() : result.run.outcomes;
      runs.put(result.version, run);
    }
    return runs;
  }

  public static Map<String, VersionTestRun> loadOutcomes(
      Path resultsDir, EnvoyVersionManifest manifest) throws IOException {
    Map<String, VersionTestRun> runs = new LinkedHashMap<String, VersionTestRun>();
    for (String version : manifest.supported) {
      VersionTestRun run = new VersionTestRun();
      run.version = version;
      runs.put(version, run);
    }
    Map<String, VersionTestRun> loaded = loadOutcomes(resultsDir);
    for (Map.Entry<String, VersionTestRun> entry : loaded.entrySet()) {
      VersionTestRun existing = runs.get(entry.getKey());
      if (existing == null) {
        runs.put(entry.getKey(), entry.getValue());
      } else {
        existing.outcomes.putAll(entry.getValue().outcomes);
      }
    }
    return runs;
  }

  public static VersionTestRun runFor(Map<String, VersionTestRun> runs, String version) {
    VersionTestRun run = runs.get(version);
    if (run == null) {
      run = new VersionTestRun();
      run.version = version;
    }
    return run;
  }

  public static String outcomeFor(VersionTestRun run, String testName) {
    if (run == null) {
      return "not_run";
    }
    String outcome = run.outcomes.get(testName);
    if (outcome == null) {
      return "not_run";
    }
    return outcome;
  }

  public static List<String> supportedVersions(EnvoyVersionManifest manifest) {
    return new ArrayList<String>();
  }

  public static List<String> versionsForReport(EnvoyVersionManifest manifest) {
    List<String> result = new ArrayList<String>();
    for (EnvoyVersions.EnvoyVersion v : manifest.versionsInOrder()) {
      result.add(v.tag);
    }
    if (result.isEmpty()) {
      result.add(manifest.defaultVersion);
    }
    return result;
  }
}
