# Envoy Conformance and Feature Coverage Plan

This document outlines a path for expanding `envoy-extproc-sdk-java` from single-version integration checks into an extensible, multi-version Envoy conformance and `ext_proc` v3 feature-coverage suite.

---

## 1. Goals & Core Testing Philosophy: Routine vs. Purposeful Conformance

Everyday development and pull-request validation have different priorities from multi-version Envoy compatibility verification:

- **Routine tests** must remain fast, small, and deterministic. This covers the Java unit and stream-fake tests (`./gradlew test`), static checks (`./gradlew check`), and the compact single-version integration smoke suite in [docker-compose.yaml](../docker-compose.yaml) run by [.github/workflows/pull-request.yaml](../.github/workflows/pull-request.yaml).
- **Conformance and feature-extension exercises** should live separately from routine tests and be **purposefully executed**. Multi-version Envoy container matrices, processing-mode permutations, HTTP/2 and trailer exercises, and protocol feature probes are heavier and should not slow down everyday commits.

Concretely, conformance exercises belong in an isolated top-level `conformance/` directory with:

- Its own module dependencies declared in the root [build.gradle](../build.gradle) test source set (the harness lives under `src/test/java`).
- Dedicated `just` recipes (`just conformance`, `just conformance-version <tag>`, `just conformance-matrix`) in [justfile](../justfile).
- A dedicated GitHub Actions workflow (`.github/workflows/conformance.yaml`) triggered purposefully: manually via `workflow_dispatch`, on release tags (`v*`), on a weekly schedule (including an `envoyproxy/envoy:dev` canary leg), when `conformance/**` changes, or on PRs carrying an explicit opt-in label (e.g., `run-conformance`).

---

## 2. Current Baseline & Structural Locks

The repository already has solid unit coverage over phases, headers, metrics, options, health checks, and stream cancellation, plus a Docker Compose integration stack and gRPC load tests. Several structural locks currently limit conformance coverage:

1. **Pinned, EOL Envoy Version**: Both [docker-compose.yaml](../docker-compose.yaml) and [Dockerfile](../Dockerfile) default to `envoyproxy/envoy:${ENVOY_VERSION:-v1.26-latest}`. Under Envoy's 12-month release lifecycle, older releases are end-of-life; the active supported minor releases as of late 2026 are `v1.37`, `v1.38`, `v1.39`, and `v1.40`. CI in [.github/workflows/pull-request.yaml](../.github/workflows/pull-request.yaml) tests only that single default tag.
2. **Why `ext_proc` v3 Backwards Compatibility Is Necessary but Not Sufficient**: Envoy's `envoy.service.ext_proc.v3` protobuf contract guarantees wire compatibility, which keeps baseline SDK framing intact across releases. However, Envoy runtime behavior on existing fields still shifts across minor versions—for example, Envoy moving from `HeaderValue.value` to `HeaderValue.raw_value`, stricter `content-length` matching on `BUFFERED` body mutations, and mid-stream reset or cancellation timing edge cases.
3. **Single `ProcessingMode` Configuration**: The existing harness and integration stacks configure only one static mode over HTTP/1.1.
4. **Action-in-Body Test Coupling**: The harness's tester decodes phase-tagged JSON request bodies to decide what mutations to apply. That design cannot easily exercise `GET` or bodyless requests, `request_body_mode: NONE`, non-JSON or binary payloads, multi-chunk `STREAMED` body processing, or `request_header_mode: SKIP`.

---

## 3. Functional Testing Needs

Functional verification falls into two parts: **(A) Conformance of features the SDK already implements**, and **(B) `ext_proc` v3 spec feature coverage** to track protocol gaps and future SDK extensions.

### Part A — Protocol Conformance of Current SDK Surface

1. **Close Existing Integration Gaps** in the harness:
   - **Body replacement size changes**: Both growing and shrinking request and response bodies in `BUFFERED` mode, confirming the SDK's updated `content-length` header handling and delivering the full mutated body end-to-end.
   - **`ImmediateResponse` across all phases**: Exercising `CancelRequest` from the request/response header, body, and trailers phases, and asserting status codes, custom response headers, and body bytes end-to-end.
   - **Header mutation actions & edge cases**: `ADD_IF_ABSENT`, `APPEND_IF_EXISTS_OR_ADD`, `OVERWRITE_IF_EXISTS_OR_ADD`, repeated headers such as `Set-Cookie` preserved in wire order, non-UTF-8 raw bytes, and `:authority` routing mutations with `ClearRouteCache`.
2. **`ProcessingMode` Permutations**: `NONE`, `BUFFERED`, `STREAMED`, `BUFFERED_PARTIAL`, `request_header_mode: SKIP`, and `SEND` trailers; HTTP/1.1 (chunked + content-length) and HTTP/2 (`h2c`) variants; mid-stream downstream disconnect; `message_timeout` expiration; `AbortOnProcessorFailure`; `ExtProcPerRoute`; multi-filter chains; and Envoy admin stats (`spurious_msgs_received == 0`) assertions.

### Part B — Envoy `ext_proc` v3 Spec Feature Audit & Extension Roadmap

Auditing the SDK against the generated envoy protobuf definitions highlights the `ext_proc` v3 protocol surface that a feature matrix should track:

| Capability (`ext_proc` v3) | Current SDK Status | Gap / Conformance Notes |
| :--- | :--- | :--- |
| 6 phase callbacks, `HeaderMutation`, `ClearRouteCache` | Supported | Needs multi-mode, HTTP/2, and trailer end-to-end coverage |
| `BodyMutation` (`Body`, `ClearBody`) | Supported | Needs `STREAMED` chunking & `BUFFERED` size-change E2E tests |
| `ImmediateResponse` (`Status`, `Headers`, `Body`) | Supported (partial) | Missing `GrpcStatus` and `Details` |
| `ObservabilityMode` (`ProcessingRequest.observability_mode`) | **Protocol Hazard** | When `true`, Envoy forbids sending a `ProcessingResponse`; the SDK currently sends `EmptyContinueResponse` |
| `ProtocolConfiguration` (`ProcessingRequest.protocol_config`) | Ignored | Sent on the first message in newer Envoys; exposing it on the context helps detect `BUFFERED` vs. `STREAMED` |
| `ModeOverride` (`ProcessingResponse.mode_override`) | Not implemented | High-value capability for dynamic per-request body/trailer toggling |
| `MetadataContext` & `DynamicMetadata` | Not implemented | High-value for reading `metadata_context` and writing `dynamic_metadata` |
| `Attributes` (`request_attributes` / `response_attributes`) | Not implemented | Read-only CEL/connection attributes |
| `OverrideMessageTimeout` | Not implemented | Allows extending per-phase timeout dynamically up to `max_message_timeout` |
| `CommonResponse_CONTINUE_AND_REPLACE` (+ `Trailers`) | Not implemented | Replace body/trailers directly during headers phase |
| `FULL_DUPLEX_STREAMED`, `StreamedImmediateResponse`, `GRPC` body mode | Not implemented | Breaks the 1-request-to-1-response loop; evaluate behind a dedicated streaming interface |

---

## 4. Testing Approach & Platform Architecture (`conformance/`)

### In-Process Topology

Instead of building and coordinating containers for every Envoy version and filter configuration via Docker Compose, invert the harness so **only Envoy runs out-of-process**:

```text
[ junit runner (HTTP/1.1, h2c, gRPC test client) ]
        │
        ├── HTTP/1.1 or h2c ──► [ Envoy (envoyproxy/envoy:vX.Y or ENVOY_BIN) ]
        │                             │
        │        ext_proc gRPC ───────┴──► [ In-process SDK ExternalProcessorServer (:ephemeral) ]
        │
        └── proxy target ────────────────► [ In-process Upstream echo server (:ephemeral, h2c + trailers + chunks) ]
```

Benefits of this architecture:

1. **Zero container image builds**: No `Dockerfile` build step or external echo server image is required during conformance runs. Both the SDK server and the upstream HTTP/1.1 + `h2c` server run inside the test process on ephemeral ports, giving tests direct programmatic control over upstream trailers, streamed chunk delays, status codes, and connection aborts.
2. **Header/ID-driven case dispatch**: Register per-case processor behavior in an in-process registry keyed by a request header (e.g., `x-conformance-case: <id>`).
3. **Single Envoy startup per version**: Generate Envoy's bootstrap JSON from the envoy protobuf structs serialized via `JsonFormat`, and use one Envoy process per matrix version with per-route `ExtProcPerRoute` overrides and separate listeners/filter chains for filter-wide settings.
4. **Dual `EnvoyRunner` Abstraction**: Container runner (default, cross-platform) launching `envoyproxy/envoy:${ENVOY_VERSION}` via Docker, and native binary runner (`ENVOY_BIN`) spawning a local Envoy binary directly.

### Separation in Local Workflows and CI

- **Local `justfile` recipes**: `just conformance`, `just conformance-version <tag>`, `just conformance-matrix`, `just conformance-report`, `just sync-envoy-versions`.
- **GitHub Actions workflows**: Keep [.github/workflows/pull-request.yaml](../.github/workflows/pull-request.yaml) focused on build/test checks and the fast single-version integration smoke run. Add `.github/workflows/conformance.yaml` with purposeful triggers (manual, weekly schedule, release tags, conformance-path changes).

---

## 5. Dependency Management Needs & Improvements

### Decouple Compile-Time API Version from Runtime Envoy Matrix

- **Compile-time** (Gradle): pins one recent envoy grpc/protocolbuffers java release to provide the v3 service and filter protobuf definitions.
- **Runtime conformance manifest** ([conformance/envoy-versions.json](conformance/envoy-versions.json)): Store the runtime Envoy target matrix in one declarative file rather than hardcoding versions inside YAML workflows or Compose files:

```json
{
  "default": "v1.40-latest",
  "supported": [
    "v1.37-latest",
    "v1.38-latest",
    "v1.39-latest",
    "v1.40-latest"
  ],
  "canary": [
    "dev"
  ]
}
```

### Automated Extensibility to New Envoy Releases

1. **Scheduled Envoy Release Sync Workflow** (weekly cron) queries the endoflife.date API and updates [conformance/envoy-versions.json], opening an automated PR when a new Envoy minor release ships or an old minor branch reaches EOL.
2. **Run the `envoyproxy/envoy:dev` canary leg weekly** (`continue-on-error: true`) so breaking Envoy filter changes surface weeks before a quarterly release.

---

## 6. Results Reporting

Avoid brittle log scraping by building reporting on the JUnit JSON test event stream paired with a declarative feature manifest:

1. **Feature Manifest** ([conformance/features.yaml](conformance/features.yaml)) declares every tracked `ext_proc` v3 capability (`id`, `category`, `description`, `sdk_status`: `supported | partial | unsupported`, `min_envoy_version`, and `test_prefix`).
2. **Structured Per-Version Artifacts**: Each CI matrix leg runs `ENVOY_VERSION=${envoy} ./gradlew test -v -json ./... > results-${envoy}.json` inside the harness and uploads `results-${envoy}.json`.
3. **Fan-In Aggregator** (`conformance/cmd/report` equivalents): Consumes the feature manifest and all `results-*.json` artifacts and produces:
   1. **CI Run Summary** (`$GITHUB_STEP_SUMMARY`): Writes a **Capability × Envoy Version** Markdown table directly on the GitHub Actions run page.
   2. **Committed Compatibility Matrix** ([CONFORMANCE.md](../CONFORMANCE.md)): Regenerates the supported-versions & feature matrix table in [CONFORMANCE.md](../CONFORMANCE.md).
   3. **Machine-Readable Summary** (`conformance-summary.json`): Compact JSON summary usable for release notes or a README compatibility badge.

---

## 7. Phased Implementation Roadmap

1. **Phase 1 — Foundation & Multi-Version Harness**: Scaffold the harness (`EnvoyRunner`, in-process upstream, header-dispatched conformance processor), add protobuf validation assertions, add `just conformance*` recipes and `.github/workflows/conformance.yaml`.
2. **Phase 2 — Full Conformance of Existing SDK Features**: Cover all `ProcessingMode` values, HTTP/1.1 and HTTP/2 (`h2c`), `ReplaceBody` size mutations, all-phase `CancelRequest`, all `HeaderAppendAction` modes, downstream cancellation, timeouts, `ExtProcPerRoute`, and Envoy admin stats assertions.
3. **Phase 3 — Reporting & Release Automation**: Add the feature manifest and report generator, cleanup of build hygiene, and the weekly Envoy release-window sync workflow.
4. **Phase 4 — SDK Feature Extensions** (Guided by the Matrix): Address `ObservabilityMode` protocol compliance, expose `ProtocolConfiguration` on the context, and evaluate incremental additions for mode overrides, metadata contexts, and dynamic metadata.
