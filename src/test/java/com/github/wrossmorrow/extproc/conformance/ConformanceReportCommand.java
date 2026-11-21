package com.github.wrossmorrow.extproc.conformance;

import com.github.wrossmorrow.extproc.conformance.EnvoyVersions.EnvoyVersionManifest;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Command-line entry point for rendering the Envoy conformance matrix. */
public class ConformanceReportCommand {

  public static void main(String[] args) throws Exception {
    String resultsDir = args.length > 0 ? args[0] : "conformance/results";
    String markdownOut = args.length > 1 ? args[1] : "/tmp/conformance-matrix.md";
    String jsonOut = args.length > 2 ? args[2] : "/tmp/conformance-summary.json";
    String conformanceMd = args.length > 3 ? args[3] : "/work/repo/CONFORMANCE.md";

    EnvoyVersionManifest manifest = EnvoyVersions.load();
    List<String> versions = new ArrayList<String>();
    for (EnvoyVersions.EnvoyVersion v : manifest.versionsInOrder()) {
      versions.add(v.tag);
    }
    if (versions.isEmpty()) {
      versions.add(manifest.defaultVersion);
    }

    Map<String, ConformanceReport.VersionTestRun> runs =
        ConformanceReport.loadOutcomes(Paths.get(resultsDir), manifest);
    ConformanceReport.SummaryReport report =
        ConformanceReport.buildReport(
            versions, ConformanceReport.loadFeatures(Paths.get("conformance/features.yaml")), runs);
    String markdown = ConformanceReport.renderMarkdown(report);
    ConformanceReport.writeMarkdown(markdownOut, report);
    ConformanceReport.writeJson(report, Paths.get(jsonOut));
    if (conformanceMd != null && !conformanceMd.isEmpty()) {
      ConformanceReport.updateConformanceDoc(conformanceMd, report, versions);
    }
  }
}
