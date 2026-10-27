package com.github.wrossmorrow.extproc;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.prometheus.client.exporter.HTTPServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Server that manages startup/shutdown of an {@code ExternalProcessor} server. */
public class ExternalProcessorServer {
  private static final Logger logger = Logger.getLogger(ExternalProcessorServer.class.getName());

  private static final String DEFAULT_EXTPROC_CLASS =
      "com.github.wrossmorrow.extproc.processors.NoOpRequestProcessor";

  public static final String EXT_PROC_SERVICE_NAME = "envoy.service.ext_proc.v3.ExternalProcessor";

  private ServerBuilder<?> builder;
  protected Server server;
  protected HealthStatusManager health;
  private int port = 50051;
  private int gracePeriodSeconds = 30;
  private Metrics metrics = new Metrics();
  private int metricsPort = 9090;
  protected HTTPServer metricsServer;
  protected List<Runnable> preStopHooks = new ArrayList<Runnable>();
  protected List<Runnable> postStopHooks = new ArrayList<Runnable>();
  protected RequestProcessor processor;

  /**
   * Whether {@link #stop()} should mark the health status NOT_SERVING before initiating graceful
   * shutdown (opted in via {@link #stopServingOnShutdown()} or {@link
   * #stopServingOnShutdownFirst()}).
   */
  protected boolean markNotServingOnStop = false;

  /** Maximum number of concurrent processing streams; zero (or less) means unlimited. */
  protected int maxConcurrentStreams;

  /** Interceptor enforcing {@link #maxConcurrentStreams}; created when a limit is configured. */
  protected MaxConcurrentStreamsInterceptor maxConcurrentStreamsInterceptor;

  /** Seconds to wait after marking the service not serving before initiating graceful stop. */
  protected int unreadyPropagationDelaySeconds;

  public ExternalProcessorServer builder() {
    return this.builder(port);
  }

  public ExternalProcessorServer builder(int port) {
    health = new HealthStatusManager();
    builder = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create());
    builder.addService(health.getHealthService());
    builder.addService(ProtoReflectionService.newInstance());
    return this;
  }

  public ExternalProcessorServer builder(ServerOptions serverOptions) {
    port = serverOptions.extProcPort;
    metricsPort = serverOptions.metricsPort;
    maxConcurrentStreams = serverOptions.maxConcurrentStreams;
    unreadyPropagationDelaySeconds = serverOptions.unreadyPropagationDelaySeconds;
    gracePeriodSeconds = serverOptions.terminationGracePeriodSeconds;
    health = new HealthStatusManager();
    builder = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create());
    builder.addService(health.getHealthService());
    builder.addService(ProtoReflectionService.newInstance());
    if (serverOptions.maxConcurrentStreams > 0) {
      maxConcurrentStreamsInterceptor =
          new MaxConcurrentStreamsInterceptor(serverOptions.maxConcurrentStreams);
      builder.intercept(maxConcurrentStreamsInterceptor);
    }
    return this;
  }

  @Deprecated
  public ExternalProcessorServer addShutdownCallback(Runnable callback) {
    logger.warning("[DEPRECATED] addShutdownCallback is deprecated, use addPostStopHook");
    preStopHooks.add(callback);
    return this;
  }

  /** Set the termination grace period for gRPC connections/requests/streams */
  @Deprecated
  public ExternalProcessorServer setGracePeriodSeconds(int seconds) {
    logger.warning(
        "[DEPRECATED] setGracePeriodSeconds is deprecated, use setTerminationGracePeriodSeconds");
    gracePeriodSeconds = seconds;
    return this;
  }

  /** Set the termination grace period for gRPC connections/requests/streams */
  public ExternalProcessorServer setTerminationGracePeriodSeconds(int seconds) {
    logger.fine("Setting termination grace period to " + seconds + " seconds");
    gracePeriodSeconds = seconds;
    return this;
  }

  /** Set the port for the Prometheus /metrics HTTP server. */
  public ExternalProcessorServer setMetricsPort(int port) {
    logger.fine("Updating metrics HTTP port to " + port);
    metricsPort = port;
    return this;
  }

  /** Set the maximum number of concurrent processing streams. */
  public ExternalProcessorServer setMaxConcurrentStreams(int maxConcurrentStreams) {
    logger.fine("Updating max concurrent streams to " + maxConcurrentStreams);
    this.maxConcurrentStreams = maxConcurrentStreams;
    if (server == null && builder != null) {
      applyMaxConcurrentStreams();
    }
    return this;
  }

  /**
   * Set the number of seconds to wait after marking the service not serving before graceful stop.
   */
  public ExternalProcessorServer setUnreadyPropagationDelaySeconds(int seconds) {
    logger.fine("Updating unready propagation delay to " + seconds + " seconds");
    unreadyPropagationDelaySeconds = seconds;
    return this;
  }

  /**
   * Add a request processor to build the service, automatically setting the shutdown method as a
   * post-stop hook. This is probably the preferred timing for processor shutdown, as processors
   * work with connections that need to drain before they can actually shutdown. E.g., if we are
   * writing to kafka in the processor, we need to know there is no more data before we flush and
   * close the kafka producer.
   */
  public ExternalProcessorServer addRequestProcessor(RequestProcessor processor) {
    logger.fine("Adding request processor \"" + processor.getName() + "\" to server");
    builder.addService(new ExternalProcessor(processor, health));
    // NOTE: processor.close will be called _after_ the server is stopped; processors
    // implementing the default close(int) delegate to shutdown()
    return addPostStopHook(() -> processor.close(gracePeriodSeconds));
  }

  /** Declare that, yes, we should set to NOT_SERVING before stopping */
  public ExternalProcessorServer stopServingOnShutdown() {
    markNotServingOnStop = true;
    return addPreStopHook(() -> setExternalProcessorNotServing());
  }

  /** Declare that we should set to NOT_SERVING _first_ before stopping */
  public ExternalProcessorServer stopServingOnShutdownFirst() {
    markNotServingOnStop = true;
    preStopHooks.add(0, () -> setExternalProcessorNotServing());
    return this;
  }

  /** Add a pre-stop hook, to run before the server is actually stopped */
  public ExternalProcessorServer addPreStopHook(Runnable callback) {
    logger.fine("Adding pre-stop hook to server");
    preStopHooks.add(callback);
    return this;
  }

  /** Add a post-stop hook, to run after server has stopped and drained */
  public ExternalProcessorServer addPostStopHook(Runnable callback) {
    logger.fine("Adding post-stop hook to server");
    postStopHooks.add(callback);
    return this;
  }

  /** Internal method for setting the extproc service as NOT_SERVING */
  private void setExternalProcessorNotServing() {
    logger.fine("Setting status to not serving");
    health.setStatus(EXT_PROC_SERVICE_NAME, ServingStatus.NOT_SERVING);
  }

  /** Set allowable message size */
  public ExternalProcessorServer setMaxInboundMessageSize(int size) {
    logger.fine("Updating max inbound message size to " + size + " bytes");
    builder.maxInboundMessageSize(size);
    return this;
  }

  /** Set allowable metadata size */
  public ExternalProcessorServer setMaxInboundMetadataSize(int size) {
    logger.fine("Updating max inbound metadata size to " + size + " bytes");
    builder.maxInboundMetadataSize(size);
    return this;
  }

  /** Set max connection age */
  public ExternalProcessorServer setMaxConnectionAge(long age, TimeUnit unit) {
    logger.fine("Setting max connection age to " + age + " " + unit);
    builder.maxConnectionAge(age, unit);
    return this;
  }

  /** Start the external processor also adding a JVM shutdown wrapper */
  public ExternalProcessorServer start() throws IOException {
    server = builder.build().start();
    logger.info("Server started, listening on " + port);
    if (metricsServer == null) {
      metricsServer = new HTTPServer(new InetSocketAddress(metricsPort), metrics.registry(), false);
    }
    logger.info("Metrics server started, listening on " + metricsPort);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread() {
              @Override
              public void run() {
                // Any logger may have been reset by its JVM shutdown hook.
                System.out.println("shutting down gRPC server since JVM is shutting down");
                try {
                  ExternalProcessorServer.this.stop();
                } catch (InterruptedException e) {
                  e.printStackTrace(System.err);
                }
                System.out.println("server shut down");
              }
            });
    return this;
  }

  /**
   * Stop the external processor, including running pre and post stop hooks CAUTION: pre stop hooks
   * will delay shutdown, so they should be fast.
   *
   * <p>Implements the graceful shutdown sequence (aligned with the Go and Rust SDKs): pre-stop
   * hooks may mark the health status NOT_SERVING, then the unready propagation delay is awaited,
   * then the gRPC server is gracefully stopped (bounded by the termination grace period, with a
   * force-stop fallback), then the metrics server is shut down, and finally the post-stop hooks
   * (e.g. processor {@code Close}) run.
   */
  public void stop() throws InterruptedException {
    if (server != null) {
      logger.fine("running preStop hooks");
      for (Runnable hook : preStopHooks) {
        hook.run();
      }

      if (markNotServingOnStop) {
        logger.fine(
            "Waiting " + unreadyPropagationDelaySeconds + " seconds for unready propagation");
        if (unreadyPropagationDelaySeconds > 0) {
          Thread.sleep(TimeUnit.SECONDS.toMillis(unreadyPropagationDelaySeconds));
        }
      }

      logger.fine("Stopping server waiting " + gracePeriodSeconds + " seconds");
      server.shutdown().awaitTermination(gracePeriodSeconds, TimeUnit.SECONDS);
      if (!server.isTerminated()) {
        logger.warning(
            "Graceful shutdown did not complete within "
                + gracePeriodSeconds
                + " seconds, forcing stop");
        server.shutdownNow();
        server.awaitTermination();
      }
    }

    if (metricsServer != null) {
      logger.fine("Stopping metrics server");
      metricsServer.stop();
      metricsServer = null;
    }

    if (server != null) {
      logger.fine("running postStop hooks");
      for (Runnable hook : postStopHooks) {
        hook.run();
      }
    }
  }

  /** Await termination on the main thread since the grpc library uses daemon threads. */
  public void blockUntilShutdown() throws InterruptedException {
    logger.fine("Blocking until shutdown");
    if (server != null) {
      server.awaitTermination();
    }
  }

  /** Instantiate a RequestProcessor from the extproc.processor_class property. */
  private static RequestProcessor getProcessorFromProperties() throws Exception {
    String processor = System.getProperty("extproc.class", DEFAULT_EXTPROC_CLASS);
    logger.fine("Running with processor \"" + processor + "\" derived from properties");
    return (RequestProcessor) Class.forName(processor).getConstructor().newInstance();
  }

  /**
   * Launches the server from the command line. This is also a naive example of usage, we can use
   * the same logic to launch the server from using code passing in the RequestProcessor we want to
   * use and chaining to add whatever other properties or hooks needed.
   */
  public static void main(String[] args) throws Exception {
    mustServe(
        ServerOptions.defaultOptions(),
        getProcessorFromProperties(),
        Logger.getLogger(ExternalProcessorServer.class.getName()));
  }

  /**
   * Serve the ExternalProcessor gRPC server from the given {@link ServerOptions}, blocking until
   * the server is shut down. Runs the graceful shutdown sequence (mark not serving → wait unready
   * propagation delay → graceful stop → metrics shutdown → processor close) when stopped.
   */
  public static ExternalProcessorServer serve(
      ServerOptions serverOptions, RequestProcessor processor, Logger logger) throws Exception {
    if (processor == null) {
      throw new IllegalArgumentException("processor is nil");
    }
    ExternalProcessorServer server = new ExternalProcessorServer();
    server.markNotServingOnStop = true;
    server.builder(serverOptions);
    server.addRequestProcessor(processor);
    server.start();
    server.blockUntilShutdown();
    return server;
  }

  /**
   * Convenience wrapper around {@link #serve(ServerOptions, RequestProcessor, Logger)} that exits
   * the JVM if the server fails to start or shut down.
   */
  public static void mustServe(
      ServerOptions serverOptions, RequestProcessor processor, Logger logger) {
    try {
      serve(serverOptions, processor, logger);
    } catch (Exception e) {
      logger.severe("extproc server failed to serve: " + e);
      System.exit(1);
    }
  }

  private void applyMaxConcurrentStreams() {
    if (builder == null) {
      return;
    }
    maxConcurrentStreamsInterceptor = new MaxConcurrentStreamsInterceptor(maxConcurrentStreams);
    builder.intercept(maxConcurrentStreamsInterceptor);
  }
}
