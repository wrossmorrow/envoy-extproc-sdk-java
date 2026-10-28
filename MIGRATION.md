# Migration notes

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

Accessors on `AllHeaders`: `get(name)`, `values(name)`, `rawValues(name)`, `has(name)`,
`splitList(name)`, `add(name, value)`, `set(name, value)`, `remove(name)`, `size()`, and
`clear()`.

The `x-extproc-duration-ns` duration header reported on end-of-stream responses
is a single value, but consumers reading it should accept either the value or the
raw value field.

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
message is the whole body; the `content-length` header is updated to match; an
empty replacement is a no-op). `replaceBodyChunk` / `clearBodyChunk` are
chunk-level operations (STREAMED semantics: they do not touch `content-length`).
The `content-length` header name is now lower-cased. If your filter config uses
`request_body_mode: BUFFERED` / `response_body_mode: BUFFERED` — as the examples
and the test harness do — prefer `replaceBody` / `clearBody`; under STREAMED, use
the chunk methods.

### Duration accumulation

The accumulated external-processing duration is now actually accumulated across
phases (previously the running sum was discarded, so `getDuration()` and the
`x-extproc-duration-ns` header reported no duration). The total is reported on
end-of-stream responses when `UpdateDurationHeader` is set, and exposed via the
new `extproc_external_processing_seconds` / `extproc_stream_duration_seconds`
metrics and histograms.

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
