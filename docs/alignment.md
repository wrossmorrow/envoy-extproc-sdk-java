# Functionality Alignment Plan: `envoy-extproc-sdk-java`

> **Status:** living planning artifact (comparison against the Go and Rust SDKs,
> gap analysis, proposed alignment plan). Not yet implemented. Companion to
> `README.md`.

This document compares the Java SDK clone in this repository against the
feature sets of the sibling SDKs that have tracked the same Envoy
`ext_proc` surface:

* **Go:** <https://github.com/wrossmorrow/envoy-extproc-sdk-go> (latest: `933ae30`
  "feat: add extproc_external_processing_seconds native histogram (#17)")
* **Rust:** <https://github.com/wrossmorrow/envoy-extproc-sdk-rust> (latest:
  `ef4d16c` "fix: rename EXTO_PROC_ env var prefix to EXTPROC_ (#29)")

Both the Go and Rust SDKs have since converged on a substantially richer
contract than the Java SDK, and the two convergences are now mutually
consistent. **The Java SDK is the lagging implementation** for the same
functionality. The goal of this plan is to bring the Java SDK to parity with
that target state, feature by feature, while preserving the existing public
surface wherever possible so that the update is a backwards-compatible
migration rather than a breaking rewrite.

User-visible context: the author of all three SDKs is the same maintainer, and
the Go/Rust SDKs have deliberately evolved through a `MIGRATION.md` (Go) that
documents intentional breaking changes. The Java SDK has no such migration
document, has no metrics, and has drifted behind on contract semantics.

---

## 1. Source-of-truth comparison

### 1.1 Contract shape (Go vs. Rust vs. Java)

| Aspect | Go (latest) | Rust (latest) | Java (current) |
|---|---|---|---|
| Phase representation | `int` constants (`REQUEST_PHASE_*`) + `RequestPhaseToString`, `defaultPhaseIfInvalid`, phase accessors on context | typed enum `RequestPhase` (`Undetermined=0 … ResponseTrailers=6`) + `as_u32`, `default_phase_if_invalid`, `request_phase_to_string` | generated protobuf `RequestCase` enum; no phase-name/number helpers on the context |
| Phase-scoped response assembly | `PhaseResponse` struct (headerMutation, bodyMutation, continueRequest, immediateResponse, clearRouteCache) reset per phase | same, via `PhaseResponse` + `reset_phase` | ad-hoc context fields (`addHeaders`, `removeHeaders`, `bodyMutation`, `immediateResponse`, `replace`, `cancelled`); no explicit `continueRequest`/`clearRouteCache` |
| Header values | `HeaderValue{Value, RawValue}`, `IsValid`, `ToEnvoyHeaderValue`, `ToEnvoyHeaderValueOption`, `BuildHeaderValuesFromMap`, multi-value `map[string][]string`, raw `map[string][][]byte`, `AllHeaders` with `Get/Values/RawValues/Has/SplitList`, no comma splitting by default | same design via `envoy-types` prost types, typed `HeaderValue`, `AllHeaders` | `Map<String, String>` only; single-value semantics, case-insensitive `TreeMap`; raw values handled via an internal `SafeHeader` during init only |
| Header ingest validation | errors when both `value` and `raw_value` set; falls back `value`→`raw_value` | same | no such validation; raw handled via `SafeHeader` |
| Multi-value / repeat headers | preserved, one entry per occurrence, wire order, no comma splitting | same | lost (map put overwrites) |
| Custom data across phases | `map[string]any` (`GetValue`/`SetValue`) | typed `http::Extensions` (`set_value<T>/get_value<T>()`) | only `processorId` (with an odd "only set once" guard) |
| Body mutation | `ReplaceBodyChunk` (chunk, no content-length), `ClearBodyChunk`, `ReplaceBody` (whole body + content-length), `ClearBody` (whole body + content-length 0); empty replace = no-op | same | `replaceBodyChunk`/`clearBodyChunk` only; no whole-body variants; no content-length handling |
| Content-Length | `kContentLength = "content-length"` constant; `ReplaceBody`/`ClearBody` overwrite it | same | no content-length handling at all; `replaceBodyChunk` leaves it alone (which is the *chunk* semantic, so the missing whole-body methods are the gap) |
| Clear route cache | `clearRouteCache`/`unClearRouteCache` → surfaced in `CommonResponse` | same | **missing entirely** |
| Continue / cancel / empty-continue / get-response | `ContinueRequest`, `CancelRequest`, `EmptyContinueResponse`, `GetResponse(phase)` returning `(resp, phase, err)` | same | cancel/getResponse present; continue and empty-continue absent |
| Immediate responses | allowed for RequestHeaders/RequestBody/ResponseHeaders/ResponseBody; trailers ignore them | same | allowed for all phases incl. trailers (minor divergence to review) |
| Error flow | phase methods return `Result`; `ErrorHandler(ctx, phase, err)`; cancellation → graceful end + ErrorHandler, **not** error; `AbortOnProcessorFailure` → abort or empty-continue; deadline exceeded = error | same (async phase methods) | exceptions thrown out of phase methods; no `ErrorHandler`; whole-stream error on any throw |
| Metrics | full Prometheus set (`extproc_*`) | same | **none** |

### 1.2 Behavioral semantics that intentionally changed upstream (and the Java gaps)

1. **Error handling / resilience** (Go `981972c`, Rust `61da29a`, Go `933ae30`): a phase error no longer necessarily fails the stream. Without `AbortOnProcessorFailure` the SDK sends an *empty continue* response and keeps the stream alive; the error is reported via `ErrorHandler` and counted in `extproc_phase_errors_total`. With `AbortOnProcessorFailure: true` the stream is aborted. Stream cancellation (client gone / envoy tore down, during receive or send, or context done between messages) ends the stream gracefully, notifies `ErrorHandler`, and is **not** an error. Deadline exceeded is a processing error.
2. **Duration** (Go `933ae30`, Rust `7e97977`, Go `981972c`): total external-processing duration is accumulated across phases and reported via `x-extproc-duration-ns` on end-of-stream responses when `UpdateDurationHeader` is set. Reported as a native histogram (`extproc_external_processing_seconds`, `extproc_stream_duration_seconds`).
3. **Processor identification** (Go `933ae30`): `x-extproc-names` response header added when `UpdateExtProcHeader` is set.
4. **Graceful shutdown** (Go `981972c` and the pre-existing Go/Rust behavior): mark health `NOT_SERVING` → wait `UnreadyPropagationDelaySeconds` → `GracefulStop` (GOAWAY, bounded by `TerminationGracePeriodSeconds`, force-stop fallback) → metrics server shutdown → processor `Close(gracePeriodSeconds)`. Previously the Java server just ran stop hooks and a fixed grace period, with no unready-propagation phase and no metrics server.
5. **Body mutation semantics** (Go `ae26c75`, Rust `bf14f20`): `ReplaceBodyChunk`/`ClearBodyChunk` are *chunk*-semantics (no content-length); new `ReplaceBody`/`ClearBody` are *whole-body* semantics that also update content-length. Empty replace is a no-op; clear clears and zeroes content-length.

### 1.3 Feature matrix (capability → status)

| # | Capability | Go | Rust | Java | Gap severity |
|---|---|---|---|---|---|
| 1 | Phase enum/helpers (`getPhase`, `getPhaseName`, invalid→default) | ✅ | ✅ | ❌ (raw `RequestCase` only) | Medium |
| 2 | Phase-scoped response assembly & reset | ✅ | ✅ | ⚠️ partial (no continue/clear-cache) | Medium |
| 3 | `AllHeaders` multi-value + raw values | ✅ | ✅ | ❌ (`Map<String,String>`) | **Critical** |
| 4 | Header ops incl. action-based update & errors | ✅ | ✅ | ⚠️ (append/add/overwrite/remove only, no action enum) | Medium |
| 5 | Custom data across phases | ✅ | ✅ | ⚠️ (processorId only) | Medium |
| 6 | Body mutation: replace/clear whole body + content-length | ✅ | ✅ | ❌ | **High** |
| 7 | Clear route cache | ✅ | ✅ | ❌ | **High** |
| 8 | Continue / cancel / empty-continue / get-response | ✅ | ✅ | ⚠️ (cancel/get ok; continue/empty-continue missing) | Medium |
| 9 | Immediate responses (phase-restricted) | ✅ | ✅ | ⚠️ (trailer phases allowed) | Low |
| 10 | Error handling & error handler, abort policy | ✅ | ✅ | ❌ (throw → stream error) | **Critical** |
| 11 | Metrics (Prometheus) | ✅ | ✅ | ❌ (none) | **High** |
| 12 | Server options (`ServerOptions`) + `Serve`/`MustServe` + graceful shutdown | ✅ | ✅ | ⚠️ (builder(`ServerOptions`), `Serve`/`MustServe`, graceful stop w/ force-stop landed; unready-delay opt-in via flag) | **High** |
| 13 | Health status management (MarkReady/MarkNotReady/Check) | ✅ | ✅ | ⚠️ (via `HealthStatusManager`; `failed`→terminal semantics to verify) | Low-Medium |
| 14 | Stream cancellation semantics | ✅ | ✅ | ⚠️ (partial fix present, needs ErrorHandler wiring) | Medium |
| 15 | Async phase methods | ✅ (sync fns returning `Result`) | ✅ (async) | ❌ (sync) | Decision |
| 16 | Tester / load-testing harness | ✅ (test/tester.go, cases.yaml) | ✅ (src/tester.rs, tests/) | ❌ (`_mocks/echo` only) | Medium |
| 17 | Examples parity (noop, timer, data, masker, router, digest, dedup, echo, trivial, loadgen) | ✅ | ✅ | ⚠️ (missing masker/router/data; timer & noop header names drift) | Medium |

---

## 2. Gap analysis (prioritized)

### 2.1 Critical — must fix before/while aligning

1. **Processor error flow (contract) — the biggest functional gap.**
   - Go/Rust: phase methods return `Result`; errors flow to `ErrorHandler(ctx, phase, err)`; `AbortOnProcessorFailure` decides abort-vs-empty-continue; cancellations are not errors.
   - Java today: phase methods are `void`; any `Throwable` escapes to `ExternalProcessor`'s stream observer and errors the whole stream. There is no `ErrorHandler`, no abort policy, no phase attribution.
   - Java fix direction: keep phase method signatures source-compatible by making exceptions the error carrier, and add to `RequestProcessor`:
     ```java
     default void errorHandler(RequestContext ctx, RequestPhase phase, Throwable err) { }
     default void close(int gracePeriodSeconds) { }
     ```
     (default methods keep existing implementations source-compatible; Java 8 default methods are permitted since `sourceCompatibility = 1.8`). Server catches phase exceptions → invokes `errorHandler` → honors `AbortOnProcessorFailure` (abort stream with error, or send empty-continue and continue). Cancellations during receive/send/context-done → graceful end, `errorHandler` notification, no error. Expose phase via context (`getPhase()`, phase number helpers).

2. **Duration accumulation bug (latent correctness bug found during comparison).**
   - `RequestContext.updateDuration` executes `this.duration.plus(duration);` without assigning — the accumulated duration is silently discarded, so `getDuration()`/`x-extproc-duration-ns` always see zero and duration headers are wrong.
   - Fix: `this.duration = this.duration.plus(duration);` (Go: `rc.Duration += dur`).

### 2.2 High — functional gaps

3. **`AllHeaders` multi-value/raw-value semantics.**
   - Replace `Map<String, String>` internals with an `AllHeaders`-style structure (`Map<String, List<String>>` + raw values, or proto-native equivalents). Keep existing `getRequestHeaders()`/`getResponseHeaders()` map getters for source compatibility, now backed by the richer structure.
   - Header ops should accept multi-values; no comma splitting by default; add `SplitList`.

4. **Body mutation alignment.**
   - Add `replaceBody(byte[])` / `clearBody()` (whole-body semantics incl. content-length update); keep `replaceBodyChunk`/`clearBodyChunk` as chunk semantics (current behavior already is chunk semantics, so no break).

5. **Clear route cache.**
   - Add `clearRouteCache()` / `unClearRouteCache()` on context and surface via `CommonResponse`.

6. **Metrics.**
   - Add a `Metrics` type (Prometheus simpleclient): `extproc_active_streams`, `extproc_errored_streams`, `extproc_phase_errors_total`, `extproc_total_streams`, `extproc_stream_duration_seconds`, `extproc_external_processing_seconds`, `extproc_total_request_headers`, `extproc_request_body_messages_total`, `extproc_total_request_trailers`, `extproc_total_response_headers`, `extproc_response_body_messages_total`, `extproc_total_response_trailers`, `extproc_empty_responses`, `extproc_request_body_bytes_total`, `extproc_response_body_bytes_total`, `extproc_response_send_errors`.
   - Wire into `ExternalProcessor`; serve `/metrics` over HTTP on `metrics_http_port`; register to a private registry.

7. **Server options & lifecycle.**
   - Add `ServerOptions` (ext_proc port, metrics port, max concurrent streams, unready propagation delay, termination grace period) with defaults, JSON/YAML loaders, and `Serve`/`MustServe` entry points.
   - Implement graceful shutdown sequence: mark health `NOT_SERVING` → wait unready propagation delay → `GracefulStop` (bounded by termination grace, force-stop fallback) → metrics shutdown → processor `Close(gracePeriodSeconds)`.
   - Add `setMaxConcurrentStreams`.
   - **Status (2026-10-27):** landed — `ServerOptions` with upstream defaults, `ExternalProcessorServer.builder(ServerOptions)`, `setMaxConcurrentStreams`, `setUnreadyPropagationDelaySeconds`, and `Serve`/`MustServe`; graceful shutdown sequence implemented in `ExternalProcessorServer.stop()` (pre-stop hooks → unready propagation wait → graceful stop w/ force-stop fallback → metrics shutdown → post-stop hooks). JSON/YAML loaders and integration tests for `Serve`/`MustServe` remain open.

### 2.3 Medium

8. **Options redesign.**
   - Adopt `UpdateExtProcHeader`, `UpdateDurationHeader`, `AbortOnProcessorFailure` (Go/Rust semantics: duration header only on end-of-stream, appended via append semantics; names header via `x-extproc-names`).
   - Retire/alias `logStream`, `logPhases`, `upstreamDurationHeader`, `downstreamDurationHeader`, `closeStreamOnEndOfStream`. Logging moves to processor logger. Keep deprecated accessors for source compatibility.

9. **Phase introspection & context completeness.**
   - Add `getPhase()`, `getPhaseName()`, phase-number mapping; add full path (`FullPath`) and keep query string/params accessors. Ensure context is safe when `processing_mode.request_header_mode: SKIP` (headers phase absent).

10. **Custom data & typed accessors** (see §1.1).

11. **Tester / load-testing harness** parity (Go `test/tester.go`, Rust `src/tester.rs` + `tests/bin/extproc-tester.rs`; Go/Rust `loadtest/` k6 scripts and plain gRPC loadgen).

12. **Examples parity** (Go/Rust example processors): `noop`, `timer`, `data`, `masker`, `router`, `digest`, `dedup`, `echo`, `trivial`, plus loadgen. Java currently lacks `masker`, `router`, `data`; has `Logging`/`Throwing` not present upstream; `LoggingRequestProcessor.getName()` incorrectly returns `"noop"`.

### 2.4 Low / review

13. **Immediate response phase restrictions** (trailers phases must not accept immediate responses) — align.
14. **Async phase methods** — Rust has async phases; Java sync. Decide whether/how to adopt (out of scope for a pure behaviour-alignment pass, noted as future direction).
15. **Health `failed()` semantics** — verify `enterTerminalState` usage vs. Go/Rust status handling.

---

## 3. Proposed alignment plan (milestones)

### Milestone 0 — Decision record & docs
- Add this plan, plus an `MIGRATION.md` for the Java SDK (mirroring the Go SDK's migration notes) covering: new options (`UpdateExtProcHeader`, `UpdateDurationHeader`, `AbortOnProcessorFailure`), new error-handler contract, `AllHeaders`, body-mutation split, metrics, server options. Version bump to reflect breaking-ish surface changes.

### Milestone 1 — Processor contract & error flow (Critical priority)
- Add default `errorHandler(RequestContext, phase, Throwable)` and `close(int gracePeriodSeconds)` to `RequestProcessor`.
- Server: catch phase exceptions → `errorHandler`; `AbortOnProcessorFailure` → abort stream w/ error, else empty-continue + continue; cancellations → graceful end + `errorHandler`, no error.
- Add context phase accessors (`getPhase`, `getPhaseName`, phase helpers).
- Tests: phase error without abort → empty continue; with abort → error; cancellation during receive/send → graceful.

### Milestone 2 — Context semantics (Critical/High priority)
- Introduce `AllHeaders`; back map getters; update header ops for multi-value/raw/action; add `Has/Get/Values/RawValues/SplitList`.
- Fix duration accumulation bug.
- Add whole-body mutation methods (`replaceBody`, `clearBody`) + content-length constant.
- Add clear-route-cache methods + wire into responses.

### Milestone 3 — Options, metrics, server lifecycle (High priority)
- `ProcessingOptions`: add `updateExtProcHeader`, `updateDurationHeader`, `abortOnProcessorFailure`; keep legacy fields/getters as deprecated aliases; JSON/YAML loaders.
- New `Metrics` type + Prometheus wiring + `/metrics` server + collection in server path.
- New `ServerOptions` + `Serve`/`MustServe` + graceful shutdown sequence; add `setMaxConcurrentStreams`.
- **Status (2026-10-27):** landed — metrics and the `ProcessingOptions` update/abort options landed earlier; server options + `Serve`/`MustServe` + graceful shutdown + `setMaxConcurrentStreams` landed in this session. Remaining: JSON/YAML loaders, `Serve`/`MustServe` tests, full end-to-end graceful-shutdown test matrix.

### Milestone 4 — Examples, harness, docs
- Add missing example processors (data, masker, router), update noop/timer/dedup/digest/echo/trivial to upstream semantics, add tester & loadgen parity, refresh `README.md`, update `docker-compose.yaml` property names if options are renamed.

### Milestone 5 — Release
- Version bump, migration notes, changelog entry, integration tests (Go-style `test/tester.go` cases) ported to Java, CI alignment.

---

## 4. Backwards-compatibility strategy for the update

The user-facing direction is **alignment**, but the migration must be **backwards compatible**:

1. **Processor contract:** keep phase methods (`void`, `Map<String,String> headers`) as the source-compatible surface; use exceptions as the error carrier and add default `errorHandler`/`close` methods. Existing processors keep compiling and behaving (except they now get error-handler/abort behavior). New capability (returning errors without throwing) can be layered later if desired (e.g. async/Result-based variant).
2. **Options:** keep existing public fields/getters (deprecated) and add the new aligned options; old env-var-based defaults keep working; new options default to the upstream defaults (`false`/`false`/`false`).
3. **Context accessors:** keep `getRequestHeaders()`/`getResponseHeaders()` returning `Map<String,String>` (backed by the new multi-value structure), and add typed accessors (`AllHeaders getAllRequestHeaders()`, etc.) for new code. Keep `replaceBodyChunk`/`clearBodyChunk` semantics unchanged; add `replaceBody`/`clearBody` as the new whole-body API.
4. **Server:** keep `ExternalProcessorServer.builder()…start()/stop()/blockUntilShutdown()` and deprecated shims working; add `Serve`/`MustServe` with `ServerOptions` alongside.
5. **Header names:** treat upstream names (`x-extproc-names`, `x-extproc-duration-ns`, `x-extproc-started-ns`, `x-extproc-finished-ns`, `x-upstream-duration-ns`, `x-extproc-request-digest`, `x-extproc-custom-data`) as canonical for the aligned implementation; keep legacy names working in example processors where cheap.

Where the upstream SDKs made **intentional** breaking changes (options rename, error flow, body semantics, migration doc), mirror the Go `MIGRATION.md` approach for Java: a `MIGRATION.md` plus a minor-version bump with deprecated aliases, rather than silent semantic replacement.

## 5. Toolchain / dependency notes for the update (things to be explicit about)

- The repo pins `sourceCompatibility = 1.8` / `targetCompatibility = 1.8` in `build.gradle`, while the task toolchain is **JDK 17** (`JAVA_VERSION=17`, `eclipse-temurin:17`-style). The checked-in `Dockerfile` still says `FROM eclipse-temurin:11`. Decide whether to bump source compatibility as part of alignment (new APIs such as `Map.entry`, `String.isBlank`, `List.of`, `Optional` may be needed); do it deliberately, not silently.
- Adding the Prometheus dependency set (`io.prometheus:prometheus`, `io.prometheus:prometheus-simpleclient-httpserver`) and possibly Jackson (for JSON/YAML option loading) will be *injected* on top of the existing toolchain. Note `grpcVersion = 1.57.2`, `protobufVersion = 3.22.3`, buf-generated code pinned to envoy `1.57.2.1...20230817...` — alignment may require version bumps; verify against the Go/Rust versions' envoy proto revisions.
- Gradle wrapper is `gradle-8.0.2`; source/target 1.8. Keep consistent; avoid introducing JDK-17-only APIs without bumping source compatibility.

## 6. Open questions

1. **Phase-method sync vs. async:** Rust phases are `async`; Java likely keeps synchronous phase methods for source compatibility. Decide whether to add an async-capable path later (or document sync-only as the Java contract).
2. **`ErrorHandler` phase parameter type:** raw `RequestCase` vs. new typed `RequestPhase` enum (Rust) — decide, though adding an enum is additive.
3. **Metrics injection:** whether `Metrics` is required or optional (nullable) in the Java server, and whether the metrics HTTP server should be optional.
4. **Behaviour for `AbortOnProcessorFailure` default** — upstream default is `false`; Java default currently effectively "fail stream". Adopt upstream default.
5. **Header-name drift:** whether to fully adopt upstream names for the example processors or keep legacy names for existing users.
6. **Tester harness fidelity:** how much of the Go/Rust `tester` (cases.yaml-driven) should be ported now vs. later.

## 7. Suggested next steps

1. Land Milestone 1 (error flow) as a small, self-contained change with tests — highest user impact and lowest risk.
2. Land Milestone 2 items that fix correctness bugs first (duration accumulation, body semantics, clear-route-cache).
3. Then options/metrics/server, then examples/harness/docs.
4. Cut a `0.3.0` release with `MIGRATION.md` and updated README.

(Milestone 3, options/metrics/server lifecycle, has now largely landed: `ServerOptions`,
`Serve`/`MustServe`, graceful shutdown sequence, and `setMaxConcurrentStreams` are implemented;
remaining work is JSON/YAML loaders and the broader graceful-shutdown test matrix.)
