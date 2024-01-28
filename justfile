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

# containerize
containerize tag="local" *flags="":
    docker build . -t {{registry}}/{{image_group}}/{{image_name}}:{{tag}} {{flags}}

# bring up compose setup
up *flags:
    docker compose up {{flags}}

# teardown compose setup
down *flags:
    docker compose down {{flags}}
