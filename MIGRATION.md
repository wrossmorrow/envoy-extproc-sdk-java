# Migration notes

## v0.5.0 (from v0.4.x)

This release is purely additive on the public API and has no breaking
source-level changes — existing `RequestProcessor` implementations compile and
run without modification (note the `BUFFERED` body `content-length` alignment
described below).

### Dynamic processing mode overrides (`mode_override`)

Added per-request dynamic processing mode overrides for `REQUEST_HEADERS` and
`RESPONSE_HEADERS` phases (requires `allow_mode_override: true` on Envoy's
`ext_proc` filter):

- Added `ModeOverride`, `ModeOverride.HeaderSendMode` (`DEFAULT`, `SEND`,
  `SKIP`), `ModeOverride.BodySendMode` (`NONE`, `STREAMED`, `BUFFERED`,
  `BUFFERED_PARTIAL`, `FULL_DUPLEX_STREAMED`, `GRPC`, plus
  `BodySendMode.fromEnvoy(ProcessingMode.BodySendMode)`), and
  `ModeOverride.toEnvoyProcessingMode()`.
- Added `RequestContext.overrideMode(ModeOverride)`,
  `RequestContext.clearModeOverride()`, `RequestContext.skipRemainingPhases()`,
  and `RequestContext.getModeOverride()` (permitted during `REQUEST_HEADERS` /
  `RESPONSE_HEADERS`, cleared between phases by `reset(...)`, and omitted on
  `ImmediateResponse`).
- Updated `MaskerRequestProcessor` and `envoy.yaml` to opt dynamically into
  `BodySendMode.BUFFERED` only for JSON payloads, and fixed
  `MaskerRequestProcessor.maskJson` so absent configured paths are never injected
  into payloads and unmatched or non-object bodies emit no `BodyMutation`.
- **Envoy notes**: `BodySendMode` has no `DEFAULT` value (`BodySendMode.NONE` is
  `0`). Additionally, when Envoy configures `allowed_override_modes`, Envoy
  resolves `DEFAULT` header/trailer fields in `response.mode_override` against
  the filter's static `processing_mode` before checking the allowlist, so
  `allowed_override_modes` entries must specify explicit `SEND`/`SKIP` values
  rather than `DEFAULT`.

### Stream protocol configuration (`protocol_config`)

- Added `ProtocolConfig` (`requestBodyMode`, `responseBodyMode`,
  `sendBodyWithoutWaitingForHeaderResponse`, `ProtocolConfig.fromProto(...)`)
  and `RequestContext.getProtocolConfig()`, captured from the first
  `ProcessingRequest` of each stream (including under
  `request_header_mode: SKIP`) and retained across all phases of that stream.
- Added `RequestContext.defaultModeOverride()`, pre-populated with Envoy's
  configured `requestBodyMode` and `responseBodyMode` from
  `getProtocolConfig()` so callers overriding a single body direction in
  `processRequestHeaders` do not zero the other direction to
  `BodySendMode.NONE` (`0`).

### Dynamic metadata and CEL attributes (`metadata_context`, `dynamic_metadata`, `attributes`)

- **Inbound (`RequestContext`)**: Added stream-accumulated `getMetadata()`
  (`Map<String, Map<String, Object>>` for
  `metadata_options.forwarding_namespaces.untyped`), `getTypedMetadata()`
  (`Map<String, Any>` for `metadata_options.forwarding_namespaces.typed`), and
  `getAttributes()` (`Map<String, Object>` flattening evaluated
  `request_attributes` and `response_attributes` from Envoy's
  `"envoy.filters.http.ext_proc"` attribute map), merged across phases of the
  stream.
- **Outbound (`RequestContext`)**: Added per-phase
  `setDynamicMetadata(String namespace, String key, Object value)`,
  `setDynamicMetadataNamespace(String namespace, Map<String, ?> values)`
  (all-or-nothing `protobuf.Struct` validation and conversion),
  `clearDynamicMetadata()`, and `getDynamicMetadata()`, attached across all 6
  phase responses as well as `ImmediateResponse` (`cancelRequest`).
- **Envoy notes**: Returned namespaces are ignored unless listed in
  `metadata_options.receiving_namespaces.untyped`, and `protobuf.Struct`
  encodes numeric values as `Double` when read back via `getMetadata()` or
  `getAttributes()`.

### Per-message timeout overrides (`override_message_timeout`)

- Added `RequestContext.overrideMessageTimeout(Duration timeout)`, which
  immediately transmits an interim PGV-valid `ProcessingResponse` mid-phase
  asking Envoy to replace the current phase's `message_timeout` timer
  (`1ms <= timeout <= max_message_timeout`, at most once per phase, and a safe
  no-op when `isObservabilityMode()` is `true`).
- **Operational warning**: `ext_proc` executes inline on Envoy's
  request/response hot path. Extending per-message timeouts pins downstream
  streams and any `BUFFERED` bodies in memory, sends an extra gRPC message on
  the stream, and multiplies concurrent active streams under load
  (`ServerOptions.maxConcurrentStreams`, Envoy cluster circuit breakers, and
  route timeouts must be sized accordingly). Envoy ignores overrides unless
  `max_message_timeout >= timeout` is configured (`0` by default = disabled).

### Whole-body `Content-Length` updates in `BUFFERED` body phases

- `RequestContext.replaceBody(...)` and `RequestContext.clearBody()` now
  overwrite `content-length` during both header phases and `BUFFERED` body
  phases (`REQUEST_BODY` / `RESPONSE_BODY`), matching Envoy's `BUFFERED` body
  handling where headers are held until the buffered body response returns.
  Under `STREAMED` body mode (where headers have already been sent), use
  `replaceBodyChunk(...)` / `clearBodyChunk()`, which never touch
  `content-length`.

## v0.4.0 (from v0.3.x)

This release has no breaking source-level API changes — existing
`RequestProcessor` implementations compile without modification.

### Observability mode (`observability_mode: true`)

`RequestContext.isObservabilityMode()` exposes
`ProcessingRequest.observability_mode`. When `true`, processor callbacks run
and SDK metrics are recorded while `ExternalProcessor` suppresses
`ProcessingResponse` sends, synthetic response headers (`updateExtProcHeader`,
`updateDurationHeader`, `downstreamDurationHeader`, `upstreamDurationHeader`),
empty-continue synthesis on phase errors, and `extproc_empty_responses`
increments. Bumped `build.buf.gen:envoyproxy_envoy_protocolbuffers_java` to
`36.2.0.1.20261028205545.5c0f5fe1eee2`.

Note Envoy's `ExternalProcessor.observability_mode` requirement: Envoy only
delivers body messages in observability mode when the body mode is `STREAMED`,
`GRPC`, or `NONE` (not `BUFFERED`).

### Header append actions enforced (`addHeader`, `appendHeader`, `updateHeader`)

Envoy's `ext_proc` filter ignores `HeaderValueOption.append_action` and evaluates
deprecated `HeaderValueOption.append` (defaulting to `false` / overwrite; see
[`envoyproxy/envoy#48886`](https://github.com/envoyproxy/envoy/issues/48886)),
which previously caused both `addHeader` and `appendHeader` to overwrite existing
headers:

- `appendHeader` / `appendHeaders` (`APPEND_IF_EXISTS_OR_ADD`) now set
  `HeaderValueOption.append = true` so Envoy appends values rather than
  overwriting (including multi-filter `x-extproc-names` and
  `x-extproc-duration-ns`).
- `addHeader` / `addHeaders` (`ADD_IF_ABSENT`) and `OVERWRITE_IF_EXISTS` are
  resolved against the phase header keys observed on the stream (carried into
  body phases). Callers that previously called `addHeader` while
  unintentionally relying on overwriting an existing header should switch to
  `overwriteHeader`.

## v0.3.0 (from v0.2.1)

The v0.3.0 line of the Java SDK brings the implementation in line with the
`ext_proc` contract now implemented by the Go and Rust SDKs, and adds the
machinery that surrounds it (metrics, server options, graceful shutdown, tester).

Where the public surface is retained, it is retained for source compatibility;
new capabilities are additive. The phase methods remain `void` and signal
failure by throwing; the SDK now routes those failures through the error handler
described below. Existing processors therefore keep compiling and keep working,
but the **default error behavior changes** (see below).

Read the **Header representation** and **RequestProcessor** sections first; they
affect most consumers.

### Processor errors no longer fail the whole stream by default

Prior releases let a `Throwable` escaping a phase method fail the processing
stream.

Now the server catches phase errors, attributes them to the phase being
processed via the new `RequestProcessor.errorHandler(RequestContext, RequestCase,
Throwable)` (which has a default no-op implementation), and honors the new
`ProcessingOptions.abortOnProcessorFailure` (default `false`):

- **default (`abortOnProcessorFailure: false`)**: send an **empty continue**
  response and keep the stream alive. The error is delivered to the error
  handler and counted in `extproc_phase_errors_total`.
- **opt-in (`abortOnProcessorFailure: true`)**: abort the stream with an error.

Stream cancellations (client going away mid-stream, Envoy tearing down, context
done between messages) end the stream gracefully and notify the error handler;
they are not errors.

Custom processors implementing the phase methods should still declare them
`void` and throw on failure; the error handler is invoked by the SDK. If a
processor wants to handle errors without throwing, an error-producing variant can
layer on top later without breaking this contract.

### Header representation (`AllHeaders`)

The context now exposes multi-value and raw-value header handling through the
new `AllHeaders` type, available via `getAllRequestHeaders()` and
`getAllResponseHeaders()`. The existing `getRequestHeaders()` / `getResponseHeaders()`
map getters remain and are now backed by that structure, so they return
multi-valued maps where a header occurred more than once.

Behavior changes that come with it:

- Values are **no longer split on commas** during ingest. Use
  `AllHeaders.splitList(name)` when a comma-delimited value is genuinely
  expected (e.g. `Accept`, `Cache-Control`, `Vary`).
- **Repeated headers are preserved** (one entry per occurrence, in wire order)
  rather than overwriting earlier values (which previously lost all but the last
  value, e.g. `Set-Cookie`).
- **Raw values** are retained alongside decoded values via
  `AllHeaders.rawValues(name)` for values that are not valid UTF-8.
- **Request ID preservation**: `x-request-id` (or the configured
  `requestIdHeaderName`) is now retained in `getRequestHeaders()` and
  `getAllRequestHeaders()` in addition to populating `getRequestId()`.
- **Outbound header mutations**: All header mutations (`addHeader`,
  `appendHeader`, `overwriteHeader`, `cancelRequest`) emit Envoy's `raw_value`
  (UTF-8 bytes) rather than the deprecated string `value` field, and lowercase
  header names (`Locale.ROOT`) for both setting and `removeHeader`. Empty
  `HeaderMutation` / `BodyMutation` submessages are omitted when no mutations
  are queued. Tests or clients inspecting `ProcessingResponse` protos directly
  should read `getRawValue()`.

Accessors on `AllHeaders`: `get(name)`, `values(name)`, `rawValues(name)`, `has(name)`,
`splitList(name)`, `add(name, value)`, `set(name, value)`, `remove(name)`, `size()`, and
`clear()`.

The `x-extproc-duration-ns` duration header is emitted using `raw_value`, and is
only injected on header and trailer phases (never on `REQUEST_BODY` /
`RESPONSE_BODY`). Consumers reading it should accept `raw_value` (with a
fallback to `value`).

### `RequestProcessor` gained `errorHandler` and `close`

Both are default methods, so existing processor implementations keep compiling
and behaving (no-op defaults) without changes:

- `errorHandler(RequestContext ctx, RequestCase phase, Throwable err)` — invoked
  when a phase error occurs (and on graceful stream cancellations, which are not
  errors).
- `close(int gracePeriodSeconds)` — invoked on shutdown after in-flight streams
  have drained; defaults to `shutdown()`.

### `Serve`, `MustServe`, and `ServerOptions`

New lifecycle surface:

```java
ExternalProcessorServer server = ExternalProcessorServer.serve(
    ServerOptions.defaultOptions(), processor, Logger.getLogger("..."));
// or
ExternalProcessorServer server = ExternalProcessorServer.MustServe(
    ServerOptions.defaultOptions(), processor, Logger.getLogger("..."));
server.start();
server.blockUntilShutdown();
```

`ServerOptions` (ext_proc port, metrics port, max concurrent streams, unready
propagation delay, termination grace period) replaces the old single-port
configuration, and is loadable from JSON or YAML. `ProcessingOptions` is
unchanged in name and remains the place for processor behavior options
(`UpdateExtProcHeader`, `UpdateDurationHeader`, `AbortOnProcessorFailure`, and
the retained legacy fields).

### Body mutation split from `Content-Length`

`replaceBody` / `clearBody` are whole-body operations (BUFFERED semantics: one
message is the whole body). Passing `null` to `replaceBody` is a no-op, while
passing an empty `byte[0]` or `""` delegates to `clearBody()`. Both methods
update the lower-cased `content-length` header **only during header phases**
(`REQUEST_HEADERS` / `RESPONSE_HEADERS`), never during `REQUEST_BODY` /
`RESPONSE_BODY` where headers may already have been sent or chunked.
`replaceBodyChunk` / `clearBodyChunk` are chunk-level operations (STREAMED
semantics: they never touch `content-length`). If your filter config uses
`request_body_mode: BUFFERED` / `response_body_mode: BUFFERED` — as the examples
and the test harness do — prefer `replaceBody` / `clearBody`; under STREAMED, use
the chunk methods.

### Immediate responses (`cancelRequest`)

`cancelRequest(status, headers, body)` now isolates `ImmediateResponse.headers`
to only the `headers` map passed to `cancelRequest` rather than inheriting prior
`addHeader` / `removeHeader` mutations queued earlier in the same phase. It also
defaults `status <= 0` to `400` (`BadRequest`) and falls back to `500`
(`InternalServerError`) for unmapped Envoy `StatusCode` numbers instead of
throwing a `NullPointerException`.

### Duration accumulation

The accumulated external-processing duration is now actually accumulated across
phases (previously the running sum was discarded, so `getDuration()` and the
`x-extproc-duration-ns` header reported no duration). The total is reported on
end-of-stream header and trailer responses (`RESPONSE_HEADERS` with
`end_of_stream` or `RESPONSE_TRAILERS`, never on body phases) when
`UpdateDurationHeader` is set, and exposed via the new
`extproc_external_processing_seconds` / `extproc_stream_duration_seconds`
metrics and histograms. Similarly, `upstreamDurationHeader` and
`downstreamDurationHeader` only inject `x-extproc-duration-ns` on header and
trailer phases.

### Shutdown behavior

`Serve` now performs a real graceful shutdown: health reports `NOT_SERVING`, the
unready propagation delay elapses, `GracefulStop` is attempted (bounded by the
termination grace period, with a force-stop fallback), then the metrics server
shuts down and the processor's `Close` runs. A `SIGTERM` now takes at least the
unready propagation delay before returning.

### Metrics

Prometheus metrics are served over HTTP on the metrics port and collected by
the server:

| Metric | Meaning |
| --- | --- |
| `extproc_active_streams` | gauge, streams in flight |
| `extproc_total_streams` | streams opened |
| `extproc_errored_streams` | streams that ended in error, counted once per stream |
| `extproc_phase_errors_total` | phase processing errors, may repeat within one stream |
| `extproc_stream_duration_seconds` | native histogram |
| `extproc_external_processing_seconds` | native histogram, total external processing time |
| `extproc_total_request_headers` | request header phases |
| `extproc_request_body_messages_total` | request body messages |
| `extproc_total_request_trailers` | request trailer phases |
| `extproc_total_response_headers` | response header phases |
| `extproc_response_body_messages_total` | response body messages |
| `extproc_total_response_trailers` | response trailer phases |
| `extproc_request_body_bytes_total` | request body bytes |
| `extproc_response_body_bytes_total` | response body bytes |
| `extproc_empty_responses` | phases that produced no response |
| `extproc_response_send_errors` | failures sending to Envoy |

---

## Toolchain

The SDK is built with a JDK 17 toolchain and targets Java 8 source compatibility
for the public API surface, so consumers on Java 8+ can compile against it.

---

## v0.2.1 and earlier (no migration needed)

Releases before v0.3.0 did not have the error-handler contract, `AllHeaders`
multi-value/raw-value surface, whole-body body mutations, metrics, server
options, or graceful shutdown described above. Processors written against those
releases keep compiling and keep running; the notable behavioral change at
v0.3.0 is the **default error behavior** described above, which now sends an
empty continue instead of failing the stream.
