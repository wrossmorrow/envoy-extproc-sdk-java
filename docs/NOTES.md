# Notes: envoy-extproc-sdk-java — comparison, gaps, and alignment working notes

> Living working notes for the ongoing effort to bring the Java SDK up to the
> feature set of the Go and Rust SDKs. Companion to [`docs/alignment.md`](alignment.md)
> (the summary/plan) and [`README.md`](../README.md) (the user-facing doc).
>
> **Status:** notes accumulated during the first comparison session; update this
> file as alignment work lands across sessions.

This session's source material (all cloned and read locally under `/tmp`):

- **Go:** <https://github.com/wrossmorrow/envoy-extproc-sdk-go> @ `933ae30`
  ("feat: add extproc_external_processing_seconds native histogram (#17)")
- **Rust:** <https://github.com/wrossmorrow/envoy-extproc-sdk-rust> @ `ef4d16c`
  ("fix: rename EXTO_PROC_ env var prefix to EXTPROC_ (#29)")

Both SDKs have converged on the same richer contract, and that contract is the
target for the Java SDK.

---

## 1. Detailed contract comparison

### 1.1 Phase representation

- **Go:** integer constants `REQUEST_PHASE_UNDETERMINED = 0`, `REQUEST_PHASE_REQUEST_HEADERS = 1`, `REQUEST_BODY = 2`, `REQUEST_TRAILERS = 3`, `RESPONSE_HEADERS = 4`, `RESPONSE_BODY = 5`, `RESPONSE_TRAILERS = 6`; helpers `defaultPhaseIfInvalid`, `RequestPhaseToString`, phase accessors on context (`GetPhase`, `GetPhaseName`).
- **Rust:** typed enum `RequestPhase` (`Undetermined` default = 0 … `ResponseTrailers = 6`) with `as_u32`, `default_phase_if_invalid`, `request_phase_to_string`; typed errors instead of ints.
- **Java:** generated protobuf `ProcessingRequest.RequestCase`; context stores the raw enum. **Gap:** no public phase accessors on `RequestContext` and no phase-name/number helpers. Proposed (source-compatible): add `getPhase()`, `getPhaseName()`, and a phase-number mapping. Long-term option: introduce a typed `RequestPhase` enum like Rust's (additive).

### 1.2 Phase-scoped response assembly

- **Go/Rust:** a `PhaseResponse` struct (`headerMutation`, `bodyMutation`, `continueRequest`, `immediateResponse`, `clearRouteCache`) reset at the start of every phase message. `GetResponse(phase)` builds the typed `ProcessingResponse` (continue, or immediate if set; trailers phases ignore immediate).
- **Java:** ad-hoc context fields (`addHeaders`, `removeHeaders`, `bodyMutation`, `immediateResponse`, `replace`, `cancelled`) and `getResponse(RequestCase)`; no explicit continue/empty-continue path.

**Gap:** add explicit continue / empty-continue. **Gap:** no clear-route-cache surface. **Gap:** Java's `reset(RequestCase)` skips the full reset for `REQUEST_HEADERS` (justified for skipped-headers streams, but the reset semantics deserve a careful test matrix).

### 1.3 Headers / `AllHeaders`

- **Go/Rust:** `HeaderValue{Value, RawValue}` with `IsValid` (both-set is invalid; `ToEnvoyHeaderValue` always emits `raw_value`, converting `Value`→bytes), `ToEnvoyHeaderValueOption(name, action)`, `BuildHeaderValuesFromMap`. Ingest: reject both-set; fall back `value`→`raw_value`; no comma splitting by default; repeated headers preserved (one entry per occurrence, wire order); keys lower-cased on ingest; accessors `Get`/`Values`/`RawValues`/`Has`/`SplitList`.
- **Java:** `Map<String, String>` via case-insensitive `TreeMap`; single value per key; raw handled internally by `SafeHeader` only during init. **Critical gap:** no multi-value/raw-value typed structure; no action-based header updates; no `SplitList`.

**Proposed Java shape (source-compatible direction):** add an `AllHeaders` class (or extend context) holding `Map<String, List<String>>` + raw values; keep `getRequestHeaders()`/`getResponseHeaders()` returning `Map<String, String>` backed by it for compat. Add `Has`, `Get`, `Values`, `RawValues`, `SplitList`, `AppendHeader`, `AddHeader`, `OverwriteHeader`, `RemoveHeader`, `AppendHeaders`, `AddHeaders`, `OverwriteHeaders`, `RemoveHeaders`, `UpdateHeader(name, value, action)` (action enum), each returning success/failure like Go/Rust.

### 1.4 Body mutations & content-length

- **Go/Rust:** `ReplaceBodyChunk` (chunk, no content-length), `ClearBodyChunk` (chunk), `ReplaceBody` (whole body + set `content-length` = len), `ClearBody` (clear + content-length = 0). Empty replace = no-op; clear clears and zeroes content-length. Content-Length constant lower-cased (`content-length`).
- **Java:** `replaceBodyChunk(byte[])` / `replaceBodyChunk(String)` / `clearBodyChunk()`; no whole-body methods. **High gap:** add `replaceBody(byte[])` and `clearBody()`.

### 1.5 Custom data

- **Go:** `map[string]any`, `GetValue(name)`, `SetValue(name, val)`.
- **Rust:** typed `http::Extensions`, `set_value<T>/get_value<T>()`.
- **Java:** only `processorId` (with a "only set once" guard). **Medium gap:** generic typed data access.

### 1.6 Error flow / resilience (highest priority gap)

- Phase methods return `Result` (Go/Rust). Errors → `ErrorHandler(ctx, phase, err)`; `AbortOnProcessorFailure` → abort stream w/ error, else send **empty continue** and keep the stream alive; phase errors counted in `extproc_phase_errors_total`.
- Stream cancellation (client gone during receive, during send, or context done between messages) → graceful end, notify `ErrorHandler`, **not** an error, no error metrics increment. Deadline exceeded = processing error.

- **Java today:** phase methods `void`; exceptions propagate to `ExternalProcessor`'s observer → whole-stream error. No `ErrorHandler`, no abort policy, no phase attribution, no graceful cancellation-via-error-handler flow. **Critical gap.**

**Proposed source-compatible Java shape** (default methods, since `sourceCompatibility = 1.8`):

```java
public interface RequestProcessor {
  String getName();
  ProcessingOptions getOptions();
  void setHealthManager(RequestProcessorHealthManager health);
  void shutdown();
  default void errorHandler(RequestContext ctx, RequestPhase phase, Throwable err) {}
  default void close(int gracePeriodSeconds) {}
  void processRequestHeaders(RequestContext ctx, Map<String, String> headers);
  // ... etc, void, throwing
}
```

Phase numbers for `errorHandler`: use existing generated `RequestCase` now (source-compatible), typed enum later.

### 1.7 Metrics (missing entirely)

Full Go/Rust set (Prometheus simpleclient; private registry; served on metrics HTTP port):

`extproc_active_streams`, `extproc_errored_streams`, `extproc_phase_errors_total`, `extproc_total_streams`, `extproc_stream_duration_seconds`, `extproc_external_processing_seconds`, `extproc_total_request_headers`, `extproc_request_body_messages_total`, `extproc_total_request_trailers`, `extproc_total_response_headers`, `extproc_response_body_messages_total`, `extproc_total_response_trailers`, `extproc_empty_responses`, `extproc_request_body_bytes_total`, `extproc_response_body_bytes_total`, `extproc_response_send_errors`.

### 1.8 Server options & lifecycle (missing)

- **Go/Rust `ServerOptions`:** `extproc_port` (50051), `metrics_http_port` (9090), `max_concurrent_streams` (1000), `unready_propagation_delay_seconds` (5), `termination_grace_period_seconds` (10); defaults; JSON/YAML loaders. `Serve(serverOptions, processor, logger) error`, `MustServe`.
- **Graceful shutdown:** mark health `NOT_SERVING` → wait unready propagation delay → `GracefulStop` (GOAWAY, bounded by termination grace, force-stop fallback) → metrics shutdown → processor `Close(gracePeriodSeconds)`.
- **Java today:** `ExternalProcessorServer.builder()…start()/stop()/blockUntilShutdown()`, ad-hoc grace period, no metrics server, no max-concurrent-streams, no unready propagation, no `Serve`/`MustServe`. **High gap.**

### 1.9 Health

- **Go/Rust:** own health service (`HealthServer` / tonic-health); status transitions; `failed` semantics. **Java:** uses `io.grpc.protobuf.services.HealthStatusManager`; `failed()` → `enterTerminalState()`. Mostly present; minor review of semantics.

### 1.10 Async phases

- **Rust:** phases are `async`. **Go:** phases are sync functions returning `Result`. **Java:** sync. **Open question** (recorded in plan): whether/how Java adopts async later.

---

## 2. Bugs / defects found during comparison (to fix as part of alignment)

1. **`RequestContext.updateDuration`** — `this.duration.plus(duration);` result discarded; duration never accumulates. Fix: assign. (Critical.)
2. **`LoggingRequestProcessor.getName()`** returns `"noop"` — likely wrong; should be `"logging"`.
3. Duration header wiring (`x-extproc-duration-ns`) currently uses per-phase durations and overwrite semantics; aligned design uses accumulated duration appended at end-of-stream when `UpdateDurationHeader` is set. (Go/Rust.)
4. Header-name drift across examples (`x-extproc-started` vs `x-extproc-started-ns`, etc.).
5. `cancelRequest` body/headers semantics vs. Go/Rust (Go passes raw bytes and builds header values via `BuildHeaderValuesFromMap`).

---

## 3. Decisions made during this session (and to re-check)

- **Backwards compatibility is the default posture of the update:** keep phase methods source-compatible (void + exceptions as error carrier; add default `errorHandler`/`close`), keep deprecated option fields, keep map-based context getters, keep chunk body semantics and add whole-body methods. Upstream's intentional breaking changes get mirrored via an `MIGRATION.md` (Go has one; Java does not) rather than silent semantic replacement.
- **Rust is the more "modern" design (typed phases, typed data, async); Go is the more conservative design (int phases, sync fns, map data).** Java is moving toward the Go/Rust converged behavior; the plan may adopt Rust-style typed surfaces additively where they don't break compat.
- **Immediate responses** allowed for `RequestHeaders`, `RequestBody`, `ResponseHeaders`, `ResponseBody` only; trailers ignore them (Go/Rust) — Java currently allows for all; to be aligned.

---

## 4. Open questions (for future sessions)

1. Async phase methods in Java (Rust has them; Java sync for compat).
2. `ErrorHandler` phase parameter type: generated `RequestCase` now vs. typed `RequestPhase` later.
3. Metrics injection: required vs. optional (nullable) `Metrics` in server.
4. Whether duration/names headers default on/off and exact env-var names for new options in Java.
5. How far to port the Go/Rust tester harness (`test/tester.go`, `src/tester.rs`, `tests/bin/extproc-tester.rs`) now vs. later.
6. Whether to fully adopt upstream header names in examples or keep legacy ones for existing users.

---

## 5. Next steps (from the plan)

1. Milestone 1 — processor error flow (error handler, abort policy, cancellation semantics, phase accessors). Highest value, lowest risk.
2. Milestone 2 — context semantics (AllHeaders, body mutations, clear route cache, duration fix, full path).
3. Milestone 3 — options, metrics, server lifecycle.
4. Milestone 4 — examples, harness, docs.
5. Milestone 5 — release (`0.3.0`) + `MIGRATION.md`.

---

## 6. Toolchain / environment notes (session-specific)

- JDK **17** (`Temurin 17.0.21`) is the active toolchain; repo `build.gradle` says `sourceCompatibility = 1.8`, `targetCompatibility = 1.8`; checked-in `Dockerfile` says `FROM eclipse-temurin:11` (inconsistent — likely part of the "heads up" about the JDK 17 setup).
- Gradle wrapper pinned to `gradle-8.0.2`; `./gradlew compileJava` **failed** in this sandbox because the wrapper distribution could not be downloaded (offline) — nothing in the repo was compiled during this comparison session.
- Dependencies of note (existing): grpc `1.57.2`, protobuf `3.22.3`, buf-generated envoy code `1.57.2.1.20230817201313...`, grpc-services/health/reflection. Future alignment may bump these.
- Adding Prometheus (`io.prometheus:prometheus`, `prometheus-simpleclient-httpserver`) and Jackson (JSON/YAML option loading) will be *injected* on top of the existing toolchain — must be deliberate and verified later via a working Gradle build.
