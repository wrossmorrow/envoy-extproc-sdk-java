package com.github.wrossmorrow.extproc;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionService;
import java.io.IOException;
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
  private Server server;
  private HealthStatusManager health;
  private int port = 50051;
  private int gracePeriodSeconds = 30;
  protected List<Runnable> preStopHooks = new ArrayList<Runnable>();
  protected List<Runnable> postStopHooks = new ArrayList<Runnable>();
  protected RequestProcessor processor;

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
    // NOTE: processor.shutdown will be called _after_ the server is stopped
    return addPostStopHook(() -> processor.shutdown());
  }

  /** Declare that, yes, we should set to NOT_SERVING before stopping */
  public ExternalProcessorServer stopServingOnShutdown() {
    return addPreStopHook(() -> setExternalProcessorNotServing());
  }

  /** Declare that we should set to NOT_SERVING _first_ before stopping */
  public ExternalProcessorServer stopServingOnShutdownFirst() {
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
   */
  public void stop() throws InterruptedException {
    if (server != null) {
      logger.fine("running preStop hooks");
      for (Runnable hook : preStopHooks) {
        hook.run();
      }
      logger.fine("Stopping server waiting " + gracePeriodSeconds + " seconds");
      server.shutdown().awaitTermination(gracePeriodSeconds, TimeUnit.SECONDS);
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
    new ExternalProcessorServer()
        .builder()
        .stopServingOnShutdownFirst()
        .addRequestProcessor(getProcessorFromProperties())
        .start()
        .blockUntilShutdown();
  }
}
