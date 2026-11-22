package com.github.wrossmorrow.extproc.conformance;

import build.buf.gen.envoy.config.bootstrap.v3.Admin;
import build.buf.gen.envoy.config.bootstrap.v3.Bootstrap;
import build.buf.gen.envoy.config.cluster.v3.Cluster;
import build.buf.gen.envoy.config.cluster.v3.Cluster.LbPolicy;
import build.buf.gen.envoy.config.common.mutation_rules.v3.HeaderMutationRules;
import build.buf.gen.envoy.config.core.v3.Address;
import build.buf.gen.envoy.config.core.v3.GrpcService;
import build.buf.gen.envoy.config.core.v3.Http1ProtocolOptions;
import build.buf.gen.envoy.config.core.v3.Http2ProtocolOptions;
import build.buf.gen.envoy.config.core.v3.SocketAddress;
import build.buf.gen.envoy.config.endpoint.v3.ClusterLoadAssignment;
import build.buf.gen.envoy.config.endpoint.v3.Endpoint;
import build.buf.gen.envoy.config.endpoint.v3.LbEndpoint;
import build.buf.gen.envoy.config.endpoint.v3.LocalityLbEndpoints;
import build.buf.gen.envoy.config.listener.v3.Filter;
import build.buf.gen.envoy.config.listener.v3.FilterChain;
import build.buf.gen.envoy.config.listener.v3.Listener;
import build.buf.gen.envoy.config.route.v3.HeaderMatcher;
import build.buf.gen.envoy.config.route.v3.Route;
import build.buf.gen.envoy.config.route.v3.RouteAction;
import build.buf.gen.envoy.config.route.v3.RouteConfiguration;
import build.buf.gen.envoy.config.route.v3.RouteMatch;
import build.buf.gen.envoy.config.route.v3.VirtualHost;
import build.buf.gen.envoy.extensions.filters.http.ext_proc.v3.ExtProcOverrides;
import build.buf.gen.envoy.extensions.filters.http.ext_proc.v3.ExtProcPerRoute;
import build.buf.gen.envoy.extensions.filters.http.ext_proc.v3.ExternalProcessor;
import build.buf.gen.envoy.extensions.filters.http.ext_proc.v3.MetadataOptions;
import build.buf.gen.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
import build.buf.gen.envoy.extensions.filters.http.router.v3.Router;
import build.buf.gen.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import build.buf.gen.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import build.buf.gen.envoy.extensions.upstreams.http.v3.HttpProtocolOptions;
import build.buf.gen.envoy.type.matcher.v3.StringMatcher;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.Duration;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.util.JsonFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Builds the Envoy v3 bootstrap configuration used by the conformance harness, mirroring the Go
 * SDK's {@code conformance/bootstrap.go}: one HTTP/1.1 + h2c listener, dual upstream/extproc
 * clusters, and per-route processing modes plus failure/timeout/chaining/observability profiles.
 */
public class EnvoyBootstrap {

  public static final String EXT_PROC_FILTER_NAME = "envoy.filters.http.ext_proc";
  public static final String EXT_PROC_SHORT_TIMEOUT_FAIL_CLOSED_NAME =
      "extproc_short_timeout_fail_closed";
  public static final String EXT_PROC_SHORT_TIMEOUT_FAIL_OPEN_NAME =
      "extproc_short_timeout_fail_open";
  public static final String EXT_PROC_ABORT_FILTER_NAME = "extproc_abort_server";
  public static final String EXT_PROC_CHAIN_FIRST_FILTER_NAME = "extproc_chain_first";
  public static final String EXT_PROC_CHAIN_SECOND_FILTER_NAME = "extproc_chain_second";
  public static final String EXT_PROC_OBSERVABILITY_FILTER_NAME = "extproc_observability";
  public static final String EXT_PROC_MODE_OVERRIDE_FILTER_NAME = "extproc_mode_override";
  public static final String EXT_PROC_MODE_OVERRIDE_ALLOWLIST_FILTER_NAME =
      "extproc_mode_override_allowlist";
  public static final String EXT_PROC_SEND_BODY_NO_WAIT_FILTER_NAME = "extproc_send_body_no_wait";
  public static final String EXT_PROC_META_FIRST_FILTER_NAME = "extproc_meta_first";
  public static final String EXT_PROC_META_SECOND_FILTER_NAME = "extproc_meta_second";
  public static final String EXT_PROC_ATTRIBUTES_FILTER_NAME = "extproc_attributes";

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
    String host =
        params.upstreamHost == null || params.upstreamHost.isEmpty()
            ? "127.0.0.1"
            : params.upstreamHost;
    int abortPort = params.abortExtProcPort == 0 ? params.extProcPort : params.abortExtProcPort;
    int secondaryPort =
        params.secondaryExtProcPort == 0 ? params.extProcPort : params.secondaryExtProcPort;

    HttpProtocolOptions downstreamProtoOpts =
        HttpProtocolOptions.newBuilder()
            .setUseDownstreamProtocolConfig(
                HttpProtocolOptions.UseDownstreamHttpConfig.newBuilder()
                    .setHttpProtocolOptions(
                        Http1ProtocolOptions.newBuilder().setEnableTrailers(true).build())
                    .setHttp2ProtocolOptions(Http2ProtocolOptions.getDefaultInstance())
                    .build())
            .build();

    HttpProtocolOptions grpcProtoOpts =
        HttpProtocolOptions.newBuilder()
            .setExplicitHttpConfig(
                HttpProtocolOptions.ExplicitHttpConfig.newBuilder()
                    .setHttp2ProtocolOptions(Http2ProtocolOptions.getDefaultInstance())
                    .build())
            .build();

    Bootstrap.StaticResources staticResources =
        Bootstrap.StaticResources.newBuilder()
            .addAllListeners(Collections.singletonList(httpListener(params)))
            .addAllClusters(
                Arrays.asList(
                    makeCluster(
                        UPSTREAM_PRIMARY, host, params.primaryUpstreamPort, downstreamProtoOpts),
                    makeCluster(
                        UPSTREAM_SECONDARY,
                        host,
                        params.secondaryUpstreamPort,
                        downstreamProtoOpts),
                    makeCluster(EXT_PROC_CLUSTER_DEFAULT, host, params.extProcPort, grpcProtoOpts),
                    makeCluster(EXT_PROC_CLUSTER_ABORT, host, abortPort, grpcProtoOpts),
                    makeCluster(EXT_PROC_CLUSTER_SECONDARY, host, secondaryPort, grpcProtoOpts)))
            .build();

    return Bootstrap.newBuilder()
        .setStaticResources(staticResources)
        .setAdmin(Admin.newBuilder().setAddress(envoyAddress(params.adminPort)).build())
        .build();
  }

  public static String bootstrapJson(BootstrapParams params) {
    Bootstrap bootstrap = buildBootstrap(params);
    TypeRegistry registry =
        TypeRegistry.newBuilder()
            .add(HttpConnectionManager.getDescriptor())
            .add(ExternalProcessor.getDescriptor())
            .add(ExtProcPerRoute.getDescriptor())
            .add(Router.getDescriptor())
            .add(HttpProtocolOptions.getDescriptor())
            .build();
    JsonFormat.Printer printer = JsonFormat.printer().usingTypeRegistry(registry);
    try {
      return printer.print(bootstrap);
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialize envoy bootstrap to json", e);
    }
  }

  private static Listener httpListener(BootstrapParams params) {
    Any disabledDefault = Any.pack(ExtProcPerRoute.newBuilder().setDisabled(true).build());

    Route secondaryRoute =
        Route.newBuilder()
            .setMatch(
                RouteMatch.newBuilder()
                    .setPrefix("/")
                    .addHeaders(
                        HeaderMatcher.newBuilder()
                            .setName(":authority")
                            .setStringMatch(
                                StringMatcher.newBuilder().setExact("upstream-secondary").build())
                            .build())
                    .build())
            .setRoute(RouteAction.newBuilder().setCluster(UPSTREAM_SECONDARY).build())
            .build();

    Route disabledRoute =
        Route.newBuilder()
            .setMatch(RouteMatch.newBuilder().setPrefix("/mode/disabled").build())
            .setRoute(RouteAction.newBuilder().setCluster(UPSTREAM_PRIMARY).build())
            .putTypedPerFilterConfig(EXT_PROC_FILTER_NAME, disabledDefault)
            .build();

    Route bufferedPartialRoute =
        makeModeRoute(
                "/mode/buffered-partial",
                ProcessingMode.newBuilder()
                    .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                    .setRequestBodyMode(ProcessingMode.BodySendMode.BUFFERED_PARTIAL)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.BUFFERED_PARTIAL)
                    .setRequestTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                    .build())
            .toBuilder()
            .setPerRequestBufferLimitBytes(UInt32Value.of(64))
            .build();

    Route bufferedRoute = makeModeRoute("/mode/buffered", bufferedProcessingMode());
    Route streamedRoute = makeModeRoute("/mode/streamed", streamedProcessingMode());
    Route trailersRoute =
        makeModeRoute(
            "/mode/trailers",
            ProcessingMode.newBuilder()
                .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                .setRequestBodyMode(ProcessingMode.BodySendMode.STREAMED)
                .setResponseBodyMode(ProcessingMode.BodySendMode.STREAMED)
                .setRequestTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SEND)
                .build());
    Route noneRoute =
        makeModeRoute(
            "/mode/none",
            ProcessingMode.newBuilder()
                .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
                .setRequestTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                .build());
    Route skipReqHeadersRoute =
        makeModeRoute(
            "/mode/skip-req-headers",
            ProcessingMode.newBuilder()
                .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SKIP)
                .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                .setResponseBodyMode(ProcessingMode.BodySendMode.BUFFERED)
                .setRequestTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                .build());

    Route timeoutClosedRoute =
        makeProfileRoute(
            "/profile/timeout-fail-closed",
            disabledDefault,
            null,
            EXT_PROC_SHORT_TIMEOUT_FAIL_CLOSED_NAME);
    Route timeoutOpenRoute =
        makeProfileRoute(
            "/profile/timeout-fail-open",
            disabledDefault,
            null,
            EXT_PROC_SHORT_TIMEOUT_FAIL_OPEN_NAME);
    Route abortClosedRoute =
        makeProfileRoute(
            "/profile/abort-fail-closed", disabledDefault, null, EXT_PROC_ABORT_FILTER_NAME);
    Route abortOpenRoute =
        makeProfileRoute(
            "/profile/abort-fail-open", disabledDefault, Boolean.TRUE, EXT_PROC_ABORT_FILTER_NAME);
    Route chainedRoute =
        makeProfileRoute(
            "/profile/chained",
            disabledDefault,
            null,
            EXT_PROC_CHAIN_FIRST_FILTER_NAME,
            EXT_PROC_CHAIN_SECOND_FILTER_NAME);
    Route observabilityRoute =
        makeProfileRoute(
            "/profile/observability", disabledDefault, null, EXT_PROC_OBSERVABILITY_FILTER_NAME);
    Route modeOverrideAllowlistRoute =
        makeProfileRouteWithMode(
            "/profile/mode-override-allowlist",
            disabledDefault,
            noneProcessingMode(),
            null,
            EXT_PROC_MODE_OVERRIDE_ALLOWLIST_FILTER_NAME);
    Route modeOverrideRespBufferedRoute =
        makeProfileRouteWithMode(
            "/profile/mode-override-resp-buffered",
            disabledDefault,
            ProcessingMode.newBuilder()
                .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                .setResponseBodyMode(ProcessingMode.BodySendMode.BUFFERED)
                .setRequestTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                .build(),
            null,
            EXT_PROC_MODE_OVERRIDE_FILTER_NAME);
    Route modeOverrideRoute =
        makeProfileRouteWithMode(
            "/profile/mode-override",
            disabledDefault,
            noneProcessingMode(),
            null,
            EXT_PROC_MODE_OVERRIDE_FILTER_NAME);
    Route sendBodyNoWaitRoute =
        makeProfileRouteWithMode(
            "/profile/send-body-without-waiting",
            disabledDefault,
            streamedProcessingMode(),
            null,
            EXT_PROC_SEND_BODY_NO_WAIT_FILTER_NAME);
    Route metadataChainRoute =
        makeProfileRoute(
            "/profile/metadata-chain",
            disabledDefault,
            null,
            EXT_PROC_META_FIRST_FILTER_NAME,
            EXT_PROC_META_SECOND_FILTER_NAME);
    Route attributesRoute =
        makeProfileRoute(
                "/profile/attributes", disabledDefault, null, EXT_PROC_ATTRIBUTES_FILTER_NAME)
            .toBuilder()
            .setName("conformance_attributes_route")
            .build();

    Route defaultRoute =
        Route.newBuilder()
            .setMatch(RouteMatch.newBuilder().setPrefix("/").build())
            .setRoute(RouteAction.newBuilder().setCluster(UPSTREAM_PRIMARY).build())
            .build();

    HttpConnectionManager hcm =
        HttpConnectionManager.newBuilder()
            .setStatPrefix("conformance_http")
            .setCodecType(HttpConnectionManager.CodecType.AUTO)
            .setHttpProtocolOptions(
                Http1ProtocolOptions.newBuilder().setEnableTrailers(true).build())
            .setHttp2ProtocolOptions(
                Http2ProtocolOptions.newBuilder()
                    .setMaxConcurrentStreams(UInt32Value.of(1024))
                    .build())
            .setRouteConfig(
                RouteConfiguration.newBuilder()
                    .setName(CONFORMANCE_ROUTES_NAME)
                    .addVirtualHosts(
                        VirtualHost.newBuilder()
                            .setName("conformance_vhost")
                            .addDomains("*")
                            .addAllRoutes(
                                Arrays.asList(
                                    secondaryRoute,
                                    disabledRoute,
                                    bufferedPartialRoute,
                                    bufferedRoute,
                                    streamedRoute,
                                    trailersRoute,
                                    noneRoute,
                                    skipReqHeadersRoute,
                                    timeoutClosedRoute,
                                    timeoutOpenRoute,
                                    abortClosedRoute,
                                    abortOpenRoute,
                                    chainedRoute,
                                    observabilityRoute,
                                    modeOverrideAllowlistRoute,
                                    modeOverrideRespBufferedRoute,
                                    modeOverrideRoute,
                                    sendBodyNoWaitRoute,
                                    metadataChainRoute,
                                    attributesRoute,
                                    defaultRoute))
                            .build())
                    .build())
            .addAllHttpFilters(
                Arrays.asList(
                    makeExtProcFilter(
                        EXT_PROC_FILTER_NAME,
                        EXT_PROC_CLUSTER_DEFAULT,
                        false,
                        millis(5000),
                        false,
                        false,
                        bufferedProcessingMode()),
                    makeExtProcFilter(
                        EXT_PROC_SHORT_TIMEOUT_FAIL_CLOSED_NAME,
                        EXT_PROC_CLUSTER_DEFAULT,
                        false,
                        millis(150),
                        false,
                        true,
                        bufferedProcessingMode()),
                    makeExtProcFilter(
                        EXT_PROC_SHORT_TIMEOUT_FAIL_OPEN_NAME,
                        EXT_PROC_CLUSTER_DEFAULT,
                        true,
                        millis(150),
                        false,
                        true,
                        bufferedProcessingMode()),
                    makeExtProcFilter(
                        EXT_PROC_ABORT_FILTER_NAME,
                        EXT_PROC_CLUSTER_ABORT,
                        false,
                        millis(5000),
                        false,
                        true,
                        bufferedProcessingMode()),
                    makeExtProcFilter(
                        EXT_PROC_CHAIN_FIRST_FILTER_NAME,
                        EXT_PROC_CLUSTER_DEFAULT,
                        false,
                        millis(5000),
                        false,
                        true,
                        bufferedProcessingMode()),
                    makeExtProcFilter(
                        EXT_PROC_CHAIN_SECOND_FILTER_NAME,
                        EXT_PROC_CLUSTER_SECONDARY,
                        false,
                        millis(5000),
                        false,
                        true,
                        bufferedProcessingMode()),
                    makeExtProcFilter(
                        EXT_PROC_OBSERVABILITY_FILTER_NAME,
                        EXT_PROC_CLUSTER_DEFAULT,
                        true,
                        millis(5000),
                        true,
                        true,
                        bufferedProcessingMode()),
                    makeExtProcFilter(
                        EXT_PROC_MODE_OVERRIDE_FILTER_NAME,
                        EXT_PROC_CLUSTER_DEFAULT,
                        false,
                        millis(5000),
                        false,
                        true,
                        noneProcessingMode(),
                        true,
                        null),
                    makeExtProcFilter(
                        EXT_PROC_MODE_OVERRIDE_ALLOWLIST_FILTER_NAME,
                        EXT_PROC_CLUSTER_DEFAULT,
                        false,
                        millis(5000),
                        false,
                        true,
                        noneProcessingMode(),
                        true,
                        Collections.singletonList(
                            ProcessingMode.newBuilder()
                                .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
                                .setRequestBodyMode(ProcessingMode.BodySendMode.BUFFERED)
                                .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
                                .setRequestTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                                .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
                                .build())),
                    makeExtProcFilter(
                        EXT_PROC_SEND_BODY_NO_WAIT_FILTER_NAME,
                        EXT_PROC_CLUSTER_DEFAULT,
                        false,
                        millis(5000),
                        false,
                        true,
                        streamedProcessingMode(),
                        false,
                        true,
                        null),
                    makeExtProcFilterWithMetadata(
                        EXT_PROC_META_FIRST_FILTER_NAME,
                        EXT_PROC_CLUSTER_DEFAULT,
                        MetadataOptions.newBuilder()
                            .setReceivingNamespaces(
                                MetadataOptions.MetadataNamespaces.newBuilder()
                                    .addUntyped("com.conformance.extproc")
                                    .build())
                            .build(),
                        null,
                        null,
                        bufferedProcessingMode()),
                    makeExtProcFilterWithMetadata(
                        EXT_PROC_META_SECOND_FILTER_NAME,
                        EXT_PROC_CLUSTER_SECONDARY,
                        MetadataOptions.newBuilder()
                            .setForwardingNamespaces(
                                MetadataOptions.MetadataNamespaces.newBuilder()
                                    .addAllUntyped(
                                        Arrays.asList(
                                            "com.conformance.extproc", "com.conformance.unallowed"))
                                    .build())
                            .build(),
                        null,
                        null,
                        bufferedProcessingMode()),
                    makeExtProcFilterWithMetadata(
                        EXT_PROC_ATTRIBUTES_FILTER_NAME,
                        EXT_PROC_CLUSTER_DEFAULT,
                        null,
                        Arrays.asList(
                            "request.path",
                            "request.url_path",
                            "request.method",
                            "request.protocol",
                            "xds.route_name"),
                        Collections.singletonList("response.code"),
                        bufferedProcessingMode()),
                    HttpFilter.newBuilder()
                        .setName("envoy.filters.http.router")
                        .setTypedConfig(Any.pack(Router.getDefaultInstance()))
                        .build()))
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

  private static Duration millis(long ms) {
    return Duration.newBuilder()
        .setSeconds(ms / 1000)
        .setNanos((int) ((ms % 1000) * 1_000_000L))
        .build();
  }

  private static ProcessingMode bufferedProcessingMode() {
    return ProcessingMode.newBuilder()
        .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
        .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
        .setRequestBodyMode(ProcessingMode.BodySendMode.BUFFERED)
        .setResponseBodyMode(ProcessingMode.BodySendMode.BUFFERED)
        .setRequestTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
        .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
        .build();
  }

  private static ProcessingMode noneProcessingMode() {
    return ProcessingMode.newBuilder()
        .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
        .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
        .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
        .setResponseBodyMode(ProcessingMode.BodySendMode.NONE)
        .setRequestTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
        .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
        .build();
  }

  private static ProcessingMode streamedProcessingMode() {
    return ProcessingMode.newBuilder()
        .setRequestHeaderMode(ProcessingMode.HeaderSendMode.SEND)
        .setResponseHeaderMode(ProcessingMode.HeaderSendMode.SEND)
        .setRequestBodyMode(ProcessingMode.BodySendMode.STREAMED)
        .setResponseBodyMode(ProcessingMode.BodySendMode.STREAMED)
        .setRequestTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
        .setResponseTrailerMode(ProcessingMode.HeaderSendMode.SKIP)
        .build();
  }

  private static final class ExtProcFilterOptions {
    String name;
    String clusterName;
    boolean failureModeAllow;
    Duration messageTimeout;
    boolean observability;
    boolean disabledByDefault;
    boolean allowModeOverride;
    boolean sendBodyWithoutWaiting;
    Iterable<ProcessingMode> allowedOverrideModes;
    ProcessingMode mode;
    MetadataOptions metadataOptions;
    List<String> requestAttributes;
    List<String> responseAttributes;
  }

  private static HttpFilter makeExtProcFilter(
      String name,
      String clusterName,
      boolean failureModeAllow,
      Duration messageTimeout,
      boolean observability,
      boolean disabledByDefault,
      ProcessingMode mode) {
    return makeExtProcFilter(
        name,
        clusterName,
        failureModeAllow,
        messageTimeout,
        observability,
        disabledByDefault,
        mode,
        false,
        false,
        null);
  }

  private static HttpFilter makeExtProcFilter(
      String name,
      String clusterName,
      boolean failureModeAllow,
      Duration messageTimeout,
      boolean observability,
      boolean disabledByDefault,
      ProcessingMode mode,
      boolean allowModeOverride,
      Iterable<ProcessingMode> allowedOverrideModes) {
    return makeExtProcFilter(
        name,
        clusterName,
        failureModeAllow,
        messageTimeout,
        observability,
        disabledByDefault,
        mode,
        allowModeOverride,
        false,
        allowedOverrideModes);
  }

  private static HttpFilter makeExtProcFilter(
      String name,
      String clusterName,
      boolean failureModeAllow,
      Duration messageTimeout,
      boolean observability,
      boolean disabledByDefault,
      ProcessingMode mode,
      boolean allowModeOverride,
      boolean sendBodyWithoutWaiting,
      Iterable<ProcessingMode> allowedOverrideModes) {
    ExtProcFilterOptions opts = new ExtProcFilterOptions();
    opts.name = name;
    opts.clusterName = clusterName;
    opts.failureModeAllow = failureModeAllow;
    opts.messageTimeout = messageTimeout;
    opts.observability = observability;
    opts.disabledByDefault = disabledByDefault;
    opts.allowModeOverride = allowModeOverride;
    opts.sendBodyWithoutWaiting = sendBodyWithoutWaiting;
    opts.allowedOverrideModes = allowedOverrideModes;
    opts.mode = mode;
    return buildExtProcFilterWithOptions(opts);
  }

  private static HttpFilter makeExtProcFilterWithMetadata(
      String name,
      String clusterName,
      MetadataOptions metadataOptions,
      List<String> requestAttributes,
      List<String> responseAttributes,
      ProcessingMode mode) {
    ExtProcFilterOptions opts = new ExtProcFilterOptions();
    opts.name = name;
    opts.clusterName = clusterName;
    opts.messageTimeout = millis(5000);
    opts.disabledByDefault = true;
    opts.mode = mode;
    opts.metadataOptions = metadataOptions;
    opts.requestAttributes = requestAttributes;
    opts.responseAttributes = responseAttributes;
    return buildExtProcFilterWithOptions(opts);
  }

  private static HttpFilter buildExtProcFilterWithOptions(ExtProcFilterOptions opts) {
    ExternalProcessor.Builder cfg =
        ExternalProcessor.newBuilder()
            .setGrpcService(
                GrpcService.newBuilder()
                    .setEnvoyGrpc(
                        GrpcService.EnvoyGrpc.newBuilder().setClusterName(opts.clusterName))
                    .setTimeout(Duration.newBuilder().setSeconds(30).build())
                    .build())
            .setFailureModeAllow(opts.failureModeAllow)
            .setObservabilityMode(opts.observability)
            .setMessageTimeout(opts.messageTimeout)
            .setMutationRules(
                HeaderMutationRules.newBuilder().setAllowAllRouting(BoolValue.of(true)).build())
            .setProcessingMode(opts.mode);
    if (opts.allowModeOverride) {
      cfg.setAllowModeOverride(true);
    }
    if (opts.sendBodyWithoutWaiting) {
      cfg.setSendBodyWithoutWaitingForHeaderResponse(true);
    }
    if (opts.allowedOverrideModes != null) {
      cfg.addAllAllowedOverrideModes(opts.allowedOverrideModes);
    }
    if (opts.metadataOptions != null) {
      cfg.setMetadataOptions(opts.metadataOptions);
    }
    if (opts.requestAttributes != null && !opts.requestAttributes.isEmpty()) {
      cfg.addAllRequestAttributes(opts.requestAttributes);
    }
    if (opts.responseAttributes != null && !opts.responseAttributes.isEmpty()) {
      cfg.addAllResponseAttributes(opts.responseAttributes);
    }
    return HttpFilter.newBuilder()
        .setName(opts.name)
        .setDisabled(opts.disabledByDefault)
        .setTypedConfig(Any.pack(cfg.build()))
        .build();
  }

  private static Any makeOverridesAny(ProcessingMode mode, Boolean failureModeAllow) {
    ExtProcOverrides.Builder overrides = ExtProcOverrides.newBuilder().setProcessingMode(mode);
    if (failureModeAllow != null) {
      overrides.setFailureModeAllow(BoolValue.of(failureModeAllow));
    }
    return Any.pack(ExtProcPerRoute.newBuilder().setOverrides(overrides.build()).build());
  }

  private static Route makeModeRoute(String prefix, ProcessingMode mode) {
    return Route.newBuilder()
        .setMatch(RouteMatch.newBuilder().setPrefix(prefix).build())
        .setRoute(RouteAction.newBuilder().setCluster(UPSTREAM_PRIMARY).build())
        .putTypedPerFilterConfig(EXT_PROC_FILTER_NAME, makeOverridesAny(mode, null))
        .build();
  }

  private static Route makeProfileRoute(
      String prefix, Any disabledDefault, Boolean failureModeAllow, String... enableFilters) {
    return makeProfileRouteWithMode(
        prefix, disabledDefault, bufferedProcessingMode(), failureModeAllow, enableFilters);
  }

  private static Route makeProfileRouteWithMode(
      String prefix,
      Any disabledDefault,
      ProcessingMode mode,
      Boolean failureModeAllow,
      String... enableFilters) {
    Route.Builder route =
        Route.newBuilder()
            .setMatch(RouteMatch.newBuilder().setPrefix(prefix).build())
            .setRoute(RouteAction.newBuilder().setCluster(UPSTREAM_PRIMARY).build())
            .putTypedPerFilterConfig(EXT_PROC_FILTER_NAME, disabledDefault);
    Any enableAny = makeOverridesAny(mode, failureModeAllow);
    for (String f : enableFilters) {
      route.putTypedPerFilterConfig(f, enableAny);
    }
    return route.build();
  }

  private static Cluster makeCluster(
      String name, String host, int port, HttpProtocolOptions httpOpts) {
    return Cluster.newBuilder()
        .setName(name)
        .setConnectTimeout(Duration.newBuilder().setSeconds(2).build())
        .setLbPolicy(LbPolicy.ROUND_ROBIN)
        .setType(Cluster.DiscoveryType.LOGICAL_DNS)
        .setDnsLookupFamily(Cluster.DnsLookupFamily.V4_ONLY)
        .putTypedExtensionProtocolOptions(
            "envoy.extensions.upstreams.http.v3.HttpProtocolOptions", Any.pack(httpOpts))
        .setLoadAssignment(
            ClusterLoadAssignment.newBuilder()
                .setClusterName(name)
                .addEndpoints(
                    LocalityLbEndpoints.newBuilder()
                        .addLbEndpoints(
                            LbEndpoint.newBuilder()
                                .setEndpoint(
                                    Endpoint.newBuilder()
                                        .setAddress(envoyAddress(host, port))
                                        .build())
                                .build())
                        .build())
                .build())
        .build();
  }

  private static Address envoyAddress(int port) {
    return envoyAddress("0.0.0.0", port);
  }

  private static Address envoyAddress(String host, int port) {
    return Address.newBuilder()
        .setSocketAddress(SocketAddress.newBuilder().setAddress(host).setPortValue(port).build())
        .build();
  }
}
