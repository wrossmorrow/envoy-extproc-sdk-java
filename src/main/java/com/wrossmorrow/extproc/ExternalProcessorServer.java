package com.wrossmorrow.extproc;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;

/** Server that manages startup/shutdown of an {@code ExternalProcessor} server. */
public class ExternalProcessorServer {
  private static final Logger logger = Logger.getLogger(ExternalProcessorServer.class.getName());
  private static final String DEFAULT_EXTPROC_CLASS =
      "com.wrossmorrow.extproc.processors.NoOpRequestProcessor";

  private ServerBuilder<?> builder;
  private Server server;
  private HealthStatusManager health;
  private int port = 50051;
  private int gracePeriodSeconds = 30;
  protected List<Function<Void, Void>> hooks = new ArrayList<Function<Void, Void>>();
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

  public ExternalProcessorServer addRequestProcessor(RequestProcessor processor) {
    builder.addService(new ExternalProcessor(processor, health));
    return this;
  }

  public ExternalProcessorServer addShutdownCallback(Function<Void, Void> callback) {
    hooks.add(callback);
    return this;
  }

  public ExternalProcessorServer setGracePeriodSeconds(int seconds) {
    gracePeriodSeconds = seconds;
    return this;
  }

  public ExternalProcessorServer start() throws IOException {
    server = builder.build().start();
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread() {
              @Override
              public void run() {
                // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                System.err.println("shutting down gRPC server since JVM is shutting down");
                try {
                  processor.shutdown();
                  ExternalProcessorServer.this.stop();
                } catch (InterruptedException e) {
                  e.printStackTrace(System.err);
                }
                System.err.println("server shut down");
              }
            });
    return this;
  }

  public void stop() throws InterruptedException {
    if (server != null) {
      server.shutdown().awaitTermination(gracePeriodSeconds, TimeUnit.SECONDS);
      for (Function<Void, Void> hook : hooks) {
        hook.apply(null);
      }
    }
  }

  /** Await termination on the main thread since the grpc library uses daemon threads. */
  public void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  /** Instantiate a RequestProcessor from the extproc.processor_class property. */
  private static RequestProcessor getProcessorFromProperties() throws Exception {
    String processor = System.getProperty("extproc.class", DEFAULT_EXTPROC_CLASS);
    return (RequestProcessor) Class.forName(processor).getConstructor().newInstance();
  }

  /** Launches the server from the command line. */
  public static void main(String[] args) throws Exception {
    new ExternalProcessorServer()
        .builder(50051)
        .addRequestProcessor(getProcessorFromProperties())
        .start()
        .blockUntilShutdown();
  }
}
