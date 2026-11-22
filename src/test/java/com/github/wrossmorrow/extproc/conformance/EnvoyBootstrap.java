package com.github.wrossmorrow.extproc.conformance;

import build.buf.gen.envoy.config.bootstrap.v3.Admin;
import build.buf.gen.envoy.config.bootstrap.v3.Bootstrap;
import build.buf.gen.envoy.config.cluster.v3.Cluster;
import build.buf.gen.envoy.config.cluster.v3.Cluster.LbPolicy;
import build.buf.gen.envoy.config.endpoint.v3.ClusterLoadAssignment;
import build.buf.gen.envoy.config.endpoint.v3.Endpoint;
import build.buf.gen.envoy.config.endpoint.v3.LbEndpoint;
import build.buf.gen.envoy.config.endpoint.v3.LocalityLbEndpoints;
import build.buf.gen.envoy.config.listener.v3.Filter;
import build.buf.gen.envoy.config.listener.v3.FilterChain;
import build.buf.gen.envoy.config.listener.v3.Listener;
import build.buf.gen.envoy.config.route.v3.Route;
import build.buf.gen.envoy.config.route.v3.RouteAction;
import build.buf.gen.envoy.config.route.v3.RouteConfiguration;
import build.buf.gen.envoy.config.route.v3.RouteMatch;
import build.buf.gen.envoy.config.route.v3.VirtualHost;
import build.buf.gen.envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor;
import build.buf.gen.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
import build.buf.gen.envoy.extensions.filters.http.router.v3.Router;
import build.buf.gen.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import build.buf.gen.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.protobuf.util.JsonFormat;
import java.util.Arrays;
import java.util.Collections;

/**
 * Builds the Envoy v3 bootstrap configuration used by the conformance harness, mirroring the Go
 * SDK's {@code conformance/bootstrap.go}: one HTTP listener, dual upstream/extproc clusters, and
 * per-route processing modes plus failure/timeout/chaining/observability profiles.
 */
public class EnvoyBootstrap {

  public static final String EXT_PROC_FILTER_NAME = "envoy.filters.http.ext_proc";
  public static final String EXT_PROC_CLUSTER_DEFAULT = "extproc_service";
  public static final String EXT_PROC_CLUSTER_ABORT = "extproc_abort_service";
  public static final String EXT_PROC_CLUSTER_SECONDARY = "extproc_secondary_service";
  public static final String UPSTREAM_PRIMARY = "upstream_primary";
  public static final String UPSTREAM_SECONDARY = "upstream_secondary";
  public static final String CONFORMANCE_ROUTES_NAME = "conformance_routes";
  public static final String CONFORMANCE_LISTENER_NAME = "conformance_listener";

  public static final class BootstrapParams {
    public String upstreamHost = "127.0.0.1";
    public int primaryUpstreamPort = 18001;
    public int secondaryUpstreamPort = 18002;
    public int extProcPort = 50051;
    public int abortExtProcPort = 50052;
    public int secondaryExtProcPort = 50053;
    public int listenerPort = 18080;
    public int adminPort = 19901;
  }

  private EnvoyBootstrap() {}

  public static Bootstrap buildBootstrap(BootstrapParams params) {
    Bootstrap.Builder builder = Bootstrap.newBuilder();
    Bootstrap.StaticResources staticResources =
        Bootstrap.StaticResources.newBuilder()
            .addAllListeners(Collections.singletonList(httpListener(params)))
            .addAllClusters(
                Arrays.asList(
                    upstreamCluster(
                        UPSTREAM_PRIMARY, params.upstreamHost, params.primaryUpstreamPort),
                    upstreamCluster(
                        UPSTREAM_SECONDARY, params.upstreamHost, params.secondaryUpstreamPort),
                    grpcUpstreamCluster(
                        EXT_PROC_CLUSTER_DEFAULT, params.upstreamHost, params.extProcPort),
                    grpcUpstreamCluster(
                        EXT_PROC_CLUSTER_ABORT, params.upstreamHost, params.abortExtProcPort),
                    grpcUpstreamCluster(
                        EXT_PROC_CLUSTER_SECONDARY,
                        params.upstreamHost,
                        params.secondaryExtProcPort)))
            .build();
    builder.setStaticResources(staticResources);
    builder.setAdmin(Admin.newBuilder().setAddress(envoyAddress(params.adminPort)).build());
    return builder.build();
  }

  public static String bootstrapJson(BootstrapParams params) {
    Bootstrap bootstrap = buildBootstrap(params);
    com.google.protobuf.TypeRegistry.Builder registryBuilder =
        com.google.protobuf.TypeRegistry.newBuilder();
    registryBuilder.add(HttpConnectionManager.getDescriptor());
    registryBuilder.add(ExternalProcessor.getDescriptor());
    registryBuilder.add(Router.getDescriptor());
    com.google.protobuf.TypeRegistry registry = registryBuilder.build();
    JsonFormat.Printer printer = JsonFormat.printer().usingTypeRegistry(registry);
    try {
      return printer.print(bootstrap);
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialize envoy bootstrap to json", e);
    }
  }

  private static Listener httpListener(BootstrapParams params) {
    HttpConnectionManager hcm =
        HttpConnectionManager.newBuilder()
            .setStatPrefix("ingress_http")
            .setCodecType(HttpConnectionManager.CodecType.AUTO)
            .setRouteConfig(
                RouteConfiguration.newBuilder()
                    .setName(CONFORMANCE_ROUTES_NAME)
                    .addVirtualHosts(
                        VirtualHost.newBuilder()
                            .setName("conformance")
                            .addDomains("*")
                            .addRoutes(
                                Route.newBuilder()
                                    .setMatch(RouteMatch.newBuilder().setPrefix("/").build())
                                    .setRoute(
                                        RouteAction.newBuilder()
                                            .setCluster(UPSTREAM_PRIMARY)
                                            .build())
                                    .build())
                            .build())
                    .build())
            .addHttpFilters(
                HttpFilter.newBuilder()
                    .setName(EXT_PROC_FILTER_NAME)
                    .setTypedConfig(Any.pack(extProcTypedConfig()))
                    .build())
            .addHttpFilters(
                HttpFilter.newBuilder()
                    .setName("envoy.filters.http.router")
                    .setTypedConfig(Any.pack(Router.getDefaultInstance()))
                    .build())
            .build();
    return Listener.newBuilder()
        .setName(CONFORMANCE_LISTENER_NAME)
        .setAddress(envoyAddress(params.listenerPort))
        .setDefaultFilterChain(
            FilterChain.newBuilder()
                .addFilters(
                    Filter.newBuilder()
                        .setName("envoy.filters.network.http_connection_manager")
                        .setTypedConfig(Any.pack(hcm))
                        .build())
                .build())
        .build();
  }

  private static ExternalProcessor extProcTypedConfig() {
    ExternalProcessor.Builder builder = ExternalProcessor.newBuilder();
    builder.setGrpcService(
        build.buf.gen.envoy.config.core.v3.GrpcService.newBuilder()
            .setEnvoyGrpc(
                build.buf.gen.envoy.config.core.v3.GrpcService.EnvoyGrpc.newBuilder()
                    .setClusterName(EXT_PROC_CLUSTER_DEFAULT)
                    .build())
            .build());
    builder.setFailureModeAllow(false);
    builder.setMessageTimeout(Duration.newBuilder().setSeconds(5).build());
    builder.setObservabilityMode(false);
    builder.setDisableClearRouteCache(false);
    builder.setProcessingMode(
        ProcessingMode.newBuilder()
            .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
            .setRequestBodyMode(ProcessingMode.BodySendMode.BUFFERED)
            .setResponseBodyMode(ProcessingMode.BodySendMode.BUFFERED)
            .setRequestTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
            .build());
    return builder.build();
  }

  private static Cluster upstreamCluster(String name, String host, int port) {
    Cluster.Builder builder = Cluster.newBuilder();
    builder.setName(name);
    builder.setConnectTimeout(Duration.newBuilder().setSeconds(2).build());
    builder.setLbPolicy(LbPolicy.ROUND_ROBIN);
    builder.setType(Cluster.DiscoveryType.LOGICAL_DNS);
    builder.setDnsLookupFamily(Cluster.DnsLookupFamily.V4_ONLY);
    builder.setLoadAssignment(
        ClusterLoadAssignment.newBuilder()
            .setClusterName(name)
            .addEndpoints(
                LocalityLbEndpoints.newBuilder()
                    .addLbEndpoints(
                        LbEndpoint.newBuilder()
                            .setEndpoint(
                                Endpoint.newBuilder().setAddress(envoyAddress(host, port)).build())
                            .build())
                    .build())
            .build());
    return builder.build();
  }

  private static Cluster grpcUpstreamCluster(String name, String host, int port) {
    Cluster.Builder builder = Cluster.newBuilder();
    builder.setName(name);
    builder.setConnectTimeout(Duration.newBuilder().setSeconds(2).build());
    builder.setLbPolicy(LbPolicy.ROUND_ROBIN);
    builder.setType(Cluster.DiscoveryType.LOGICAL_DNS);
    builder.setDnsLookupFamily(Cluster.DnsLookupFamily.V4_ONLY);
    builder.setHttp2ProtocolOptions(
        build.buf.gen.envoy.config.core.v3.Http2ProtocolOptions.getDefaultInstance());
    builder.setLoadAssignment(
        ClusterLoadAssignment.newBuilder()
            .setClusterName(name)
            .addEndpoints(
                LocalityLbEndpoints.newBuilder()
                    .addLbEndpoints(
                        LbEndpoint.newBuilder()
                            .setEndpoint(
                                Endpoint.newBuilder().setAddress(envoyAddress(host, port)).build())
                            .build())
                    .build())
            .build());
    return builder.build();
  }

  private static build.buf.gen.envoy.config.core.v3.Address envoyAddress(int port) {
    return build.buf.gen.envoy.config.core.v3.Address.newBuilder()
        .setSocketAddress(
            build.buf.gen.envoy.config.core.v3.SocketAddress.newBuilder()
                .setAddress("0.0.0.0")
                .setPortValue(port)
                .build())
        .build();
  }

  private static build.buf.gen.envoy.config.core.v3.Address envoyAddress(String host, int port) {
    return build.buf.gen.envoy.config.core.v3.Address.newBuilder()
        .setSocketAddress(
            build.buf.gen.envoy.config.core.v3.SocketAddress.newBuilder()
                .setAddress(host)
                .setPortValue(port)
                .build())
        .build();
  }
}
