registry := "ghcr.io"
image_group := "wrossmorrow"
image_name := "envoy-extproc-sdk-java"

default:
    just --list

# clean build
clean:
    ./gradlew clean

# format code
format:
    ./gradlew spotlessApply

# test code
test *flags:
    ./gradlew test {{flags}}

# build
build *flags:
    ./gradlew build {{flags}}

# run
run *props:
    java -Djava.util.logging.config.file=logging.properties \
        {{props}} -jar build/libs/extproc-*.jar

# containerize
containerize tag="local" *flags="":
    docker build . -t {{registry}}/{{image_group}}/{{image_name}}:{{tag}} {{flags}}

# bring up compose setup
up *flags:
    docker compose up {{flags}}

# teardown compose setup
down *flags:
    docker compose down {{flags}}

# run the envoy conformance harness (requires docker or ENVOY_BIN)
conformance *flags:
    CONFORMANCE_REQUIRE_ENVOY=1 ./gradlew test --tests "com.github.wrossmorrow.extproc.conformance.*" {{flags}}

# run the envoy conformance harness against a specific envoy version
conformance-version version="v1.40-latest" *flags:
    CONFORMANCE_REQUIRE_ENVOY=1 ENVOY_VERSION={{version}} ./gradlew test --tests "com.github.wrossmorrow.extproc.conformance.*" {{flags}}

# run the full envoy conformance matrix
conformance-matrix *flags:
    #!/usr/bin/env bash
    set -euo pipefail
    for v in $(jq -r '.supported[]' conformance/envoy-versions.json); do
        echo "=== Envoy Conformance: ${v} ==="
        CONFORMANCE_REQUIRE_ENVOY=1 ENVOY_VERSION="${v}" ./gradlew test --tests "com.github.wrossmorrow.extproc.conformance.*" {{flags}}
    done

# generate the envoy conformance matrix markdown report
conformance-report *flags:
    #!/usr/bin/env bash
    set -euo pipefail
    rm -f conformance/results/results-*.json
    just conformance-matrix {{flags}}
    ./gradlew conformanceReport

# sync the envoy version manifest from endoflife.date
sync-envoy-versions eol_json="https://endoflife.date/api/envoy.json":
    curl -sL {{eol_json}} > /tmp/eol-envoy.json
    ./gradlew syncEnvoyVersions

# show the envoy version manifest
envoy-versions:
    cat conformance/envoy-versions.json
