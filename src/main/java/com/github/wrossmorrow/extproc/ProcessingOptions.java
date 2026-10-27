package com.github.wrossmorrow.extproc;

public class ProcessingOptions {
  public Boolean logStream;
  public Boolean logPhases;
  public Boolean upstreamDurationHeader;
  public Boolean downstreamDurationHeader;
  public Boolean closeStreamOnEndOfStream;
  public Boolean abortOnProcessorFailure;
  public Boolean updateExtProcHeader;
  public Boolean updateDurationHeader;

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
