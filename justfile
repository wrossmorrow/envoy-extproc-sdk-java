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
    ./gradlew test --tests "com.github.wrossmorrow.extproc.conformance.*"

# run the envoy conformance harness against a specific envoy version
conformance-version version="v1.40-latest" *flags:
    ENVOY_VERSION={{version}} ./gradlew test --tests "com.github.wrossmorrow.extproc.conformance.*"

# run the full envoy conformance matrix
conformance-matrix *flags:
    ./gradlew test --tests "com.github.wrossmorrow.extproc.conformance.*"

# generate the envoy conformance matrix markdown report
conformance-report:
    ./gradlew conformanceReport

# sync the envoy version manifest from endoflife.date
sync-envoy-versions eol_json="https://endoflife.date/api/envoy.json":
    curl -sL {{eol_json}} > /tmp/eol-envoy.json
    ./gradlew syncEnvoyVersions

# show the envoy version manifest
envoy-versions:
    cat conformance/envoy-versions.json
