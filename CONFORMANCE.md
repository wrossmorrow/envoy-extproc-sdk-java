# Envoy Compatibility and `ext_proc` Feature Coverage

This page tracks the Envoy versions and `envoy.service.ext_proc.v3` features supported and verified by `envoy-extproc-sdk-java`.

Verification in this repository is intentionally split into two tiers:

- **Routine tests**: Fast unit/stream-fake tests (`./gradlew test`) and the compact single-version integration smoke suite in [docker-compose.yaml](docker-compose.yaml) run on every pull request in [.github/workflows/pull-request.yaml](.github/workflows/pull-request.yaml).
- **Purposeful conformance exercises**: Multi-version Envoy compatibility matrices, processing-mode permutations, HTTP/2 and trailer coverage, and new spec-extension probes live in an isolated [conformance/](conformance) suite executed intentionally (on demand, on release tags, on schedule, or on conformance changes via [.github/workflows/conformance.yaml](.github/workflows/conformance.yaml)) rather than blocking every routine commit. See [docs/CONFORMANCE_PLAN.md](docs/CONFORMANCE_PLAN.md) for the full architecture and roadmap.

---

## Version Baseline

| Component | Current Pin / Target | Notes |
| :--- | :--- | :--- |
| Java Toolchain (`build.gradle`) | `sourceCompatibility = 1.8` | Minimum module version used by the SDK and the conformance harness |
| Envoy `ext_proc` v3 protobufs (Gradle) | envoy grpc java `1.57.2.1.20230817201313...`, envoy protocolbuffers java `36.2.0.1...` | Compile-time `envoy.service.ext_proc.v3` definitions |
| Routine CI Envoy ([docker-compose.yaml](docker-compose.yaml)) | `envoyproxy/envoy:${ENVOY_VERSION:-v1.26-latest}` | Default tag in [docker-compose.yaml](docker-compose.yaml); override with `ENVOY_VERSION` |
| Multi-Version Matrix ([conformance/envoy-versions.json](conformance/envoy-versions.json)) | `v1.37` – `v1.40` + `dev` | Active 12-month supported Envoy minor releases plus `dev` canary |

> [!NOTE]
> Phases 1–3 of [docs/CONFORMANCE_PLAN.md](docs/CONFORMANCE_PLAN.md) are complete: the [conformance/](conformance) harness, [conformance/features.yaml](conformance/features.yaml) manifest, report generator (`just conformance-report`), deterministic Docker/CI module checks, and automated Envoy release-window sync ([.github/workflows/envoy-versions-sync.yaml](.github/workflows/envoy-versions-sync.yaml)) are in place. Phase 4 covers the flagged SDK gaps and new v3 spec extensions.

---

## `ext_proc` v3 Feature Coverage Matrix

<!-- BEGIN GENERATED CONFORMANCE MATRIX -->
# Envoy conformance matrix

Outcomes are generated from Envoy conformance runs; see `CONFORMANCE.md`.

| Feature | Category | SDK status | v1.37-latest | v1.38-latest | v1.39-latest | v1.40-latest |
| --- | --- | --- | --- | --- | --- | --- |
| headers_send | Headers | supported | pass | pass | pass | pass |
| headers_skip_request | Headers | supported | pass | pass | pass | pass |
| headers_representation | Headers | supported | pass | pass | pass | pass |
| headers_mutations_core | Headers | supported | pass | pass | pass | pass |
| headers_append_action | Headers | partial | skipped | skipped | skipped | skipped |
| body_none | Bodies | supported | pass | pass | pass | pass |
| body_buffered | Bodies | supported | pass | pass | pass | pass |
| body_streamed | Bodies | supported | pass | pass | pass | pass |
| body_buffered_partial | Bodies | supported | pass | pass | pass | pass |
| trailers_http1_and_h2c | Trailers | supported | pass | pass | pass | pass |
| routing_clear_route_cache | Routing & Local Reply | supported | pass | pass | pass | pass |
| immediate_response | Routing & Local Reply | partial | pass | pass | pass | pass |
| per_route_disabled | Routing & Local Reply | supported | pass | pass | pass | pass |
| downstream_cancellation | Resilience & Chaining | supported | pass | pass | pass | pass |
| message_timeout_and_fail_open | Resilience & Chaining | supported | pass | pass | pass | pass |
| processor_failure_modes | Resilience & Chaining | supported | pass | pass | pass | pass |
| multi_filter_chaining | Resilience & Chaining | supported | pass | pass | pass | pass |
| observability_mode | Spec Extensions | protocol_gap | skipped | skipped | skipped | skipped |
| protocol_config | Spec Extensions | unsupported | not_run | not_run | not_run | not_run |
| mode_override | Spec Extensions | supported | pass | pass | pass | pass |
| dynamic_metadata_and_attributes | Spec Extensions | unsupported | not_run | not_run | not_run | not_run |
| override_message_timeout | Spec Extensions | unsupported | not_run | not_run | not_run | not_run |
| continue_and_replace_and_full_duplex | Spec Extensions | unsupported | not_run | not_run | not_run | not_run |

<!-- END GENERATED CONFORMANCE MATRIX -->

## Running Verification Today

Run the routine unit and stream-fake test suite (no Docker required):

```sh
just test
```

Run the routine Docker Compose integration suite (defaults to `ENVOY_VERSION=v1.26-latest`):

```sh
just integration-test
```

Override the Envoy container tag locally to exercise a newer Envoy release against the existing integration cases:

```sh
ENVOY_VERSION=v1.40-latest just integration-test
```

Run the purposeful multi-version conformance suite (requires Docker or `ENVOY_BIN`), or its fast zero-Docker harness self-tests:

```sh
just conformance
just conformance-version v1.40-latest
just conformance-matrix
just conformance-report
just sync-envoy-versions
```

Run the smoke load tests (full chain via k6, or direct gRPC via `loadgen`):

```sh
just loadtest-smoke
just loadtest-grpc-smoke
```

For the multi-version conformance design, dependency lifecycle policy, and reporting architecture, see [docs/CONFORMANCE_PLAN.md](docs/CONFORMANCE_PLAN.md).
