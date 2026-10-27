package com.github.wrossmorrow.extproc;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ProcessingOptions {
  public Boolean logStream;
  public Boolean logPhases;
  public Boolean upstreamDurationHeader;
  public Boolean downstreamDurationHeader;
  public Boolean closeStreamOnEndOfStream;

  @JsonProperty("UpdateExtProcHeader")
  @JsonAlias("updateExtProcHeader")
  public Boolean updateExtProcHeader;

  @JsonProperty("UpdateDurationHeader")
  @JsonAlias("updateDurationHeader")
  public Boolean updateDurationHeader;

  @JsonProperty("AbortOnProcessorFailure")
  @JsonAlias("abortOnProcessorFailure")
  public Boolean abortOnProcessorFailure;

  public ProcessingOptions() {
    logStream = Boolean.getBoolean("extproc.logs.stream");
    logPhases = Boolean.getBoolean("extproc.logs.phases");
    upstreamDurationHeader = Boolean.getBoolean("extproc.upstream.headers.duration");
    downstreamDurationHeader = Boolean.getBoolean("extproc.downstream.headers.duration");
    closeStreamOnEndOfStream = Boolean.getBoolean("extproc.streams.closeOnEndOfStream");
    abortOnProcessorFailure = Boolean.getBoolean("extproc.abort_on_processor_failure");
    updateExtProcHeader = Boolean.getBoolean("extproc.update.ext_proc_header");
    updateDurationHeader = Boolean.getBoolean("extproc.update.duration_header");
  }

  /** Returns the default processing options (no updates, no aborts), matching the Go/Rust SDKs. */
  public static ProcessingOptions newDefaultProcessingOptions() {
    ProcessingOptions options = new ProcessingOptions();
    options.logStream = false;
    options.logPhases = false;
    options.upstreamDurationHeader = false;
    options.downstreamDurationHeader = false;
    options.closeStreamOnEndOfStream = false;
    options.abortOnProcessorFailure = false;
    options.updateExtProcHeader = false;
    options.updateDurationHeader = false;
    return options;
  }

  /**
   * Loads processing options from a JSON file, e.g. {@code {"UpdateExtProcHeader": true,
   * "UpdateDurationHeader": true, "AbortOnProcessorFailure": true}}.
   */
  public static ProcessingOptions fromJson(String filePath) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    try (InputStream in = Files.newInputStream(Paths.get(filePath))) {
      return mapper.readValue(in, ProcessingOptions.class);
    }
  }

  /**
   * Loads processing options from a YAML file, e.g. {@code UpdateExtProcHeader: true
   * UpdateDurationHeader: true AbortOnProcessorFailure: true}.
   */
  public static ProcessingOptions fromYaml(String filePath) throws IOException {
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    try (InputStream in = Files.newInputStream(Paths.get(filePath))) {
      return mapper.readValue(in, ProcessingOptions.class);
    }
  }

  public String toString() {
    StringBuilder opts = new StringBuilder();
    opts.append(" logStream(" + logStream + ")");
    opts.append(" logPhases(" + logPhases + ")");
    opts.append(" upstreamDurationHeader(" + upstreamDurationHeader + ")");
    opts.append(" downstreamDurationHeader(" + downstreamDurationHeader + ")");
    opts.append(" closeStreamOnEndOfStream(" + closeStreamOnEndOfStream + ")");
    opts.append(" abortOnProcessorFailure(" + abortOnProcessorFailure + ")");
    opts.append(" updateExtProcHeader(" + updateExtProcHeader + ")");
    opts.append(" updateDurationHeader(" + updateDurationHeader + ")");
    return getClass().getName() + "@" + Integer.toHexString(hashCode()) + ": " + opts.toString();
  }
}
