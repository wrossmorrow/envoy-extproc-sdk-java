package com.github.wrossmorrow.extproc;

public class ProcessingOptions {
  public Boolean logStream;
  public Boolean logPhases;
  public Boolean upstreamDurationHeader;
  public Boolean downstreamDurationHeader;

  public ProcessingOptions() {
    logStream = Boolean.getBoolean("extproc.logs.stream");
    logPhases = Boolean.getBoolean("extproc.logs.phases");
    upstreamDurationHeader = Boolean.getBoolean("extproc.upstream.headers.duration");
    downstreamDurationHeader = Boolean.getBoolean("extproc.downstream.headers.duration");
  }

  public String toString() {
    StringBuilder opts = new StringBuilder();
    opts.append(" logStream(" + logStream + ")");
    opts.append(" logPhases(" + logPhases + ")");
    opts.append(" upstreamDurationHeader(" + upstreamDurationHeader + ")");
    opts.append(" downstreamDurationHeader(" + downstreamDurationHeader + ")");
    return getClass().getName() + "@" + Integer.toHexString(hashCode()) + ": " + opts.toString();
  }
}
