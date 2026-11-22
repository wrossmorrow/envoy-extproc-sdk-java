# An Envoy ExternalProcessor SDK (java)

## Statement on AI Use

All commits authored by `wrossmorrow-pi-bot` (or perhaps `flint-agent`) are prepared by an agent using [Recursive's flint](https://huggingface.co/RecursiveSI/flint) and the [pi harness](https://pi.dev/) in a [local sandbox](https://github.com/wrossmorrow/local-flint-pi). If other support is used the commits or PR descriptions should clarify. This setup was, in particular, used to draft from the (hand-written) `0.2.1` release up to a `0.3.0` release including fixes and improvements observed using the `go` SDK during 2024-2026. 

## Overview

[`envoy`](https://www.envoyproxy.io/), one of the most powerful and widely used reverse proxies, is able to query an [ExternalProcessor](https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/ext_proc_filter) in it's filter chain. Such a processor is a gRPC service that streams messages back and forth to modify HTTP requests being processed by `envoy`. This functionality opens the door to quickly and robustly implemently customized functionality at the edge, instead of in targeted services. While powerful, implementing these services still requires dealing with complicated `envoy` specs, managing information sharing across request phases, and an understanding of gRPC, none of which are exactly straightforward. 

**The purpose of this SDK is to make development of ExternalProcessors (more) easy**. This SDK _certainly_ won't supply the most _performant_ edge functions. Much better performance will come from eschewing the ease-of-use functionality here by using a [WASM plugin](https://www.envoyproxy.io/docs/envoy/latest/api-v3/extensions/wasm/v3/wasm.proto) or registered [custom filter binary](https://github.com/envoyproxy/envoy-filter-example). Optimal performance isn't necessarily our goal; usability, maintainability, and low time-to-functionality is, and those aspects can often be more important than minimal request latency. In particular, our hope is you can use this SDK with a very minimal understanding of gRPC and basically no knowledge of the `envoy` `proto` specs for an external processor. 

We attempt to achieve this ease largely by masking some of the details behind the datastructures `envoy` uses, which are effective but verbose and idiosyncratic, and supplying the required gRPC code for the service implementation. Each request to `envoy` generates a bidirectional gRPC stream (with at most 6 messages) and sends, in turn, data concerning request headers, request body, request trailers, response headers, response body, and response trailers (if `envoy` is configured to send all phases). The idea here is to supply functions for each phase that operate on a context and more generically typed data suitable for each phase. (See details below.)

Several examples are provided here in the [examples](#examples), which can be reviewed to examine usage patterns. 

## Usage

### TL;DR

Implement the `extproc.RequestProcessor` interface, and pass an instance to the canned server:
```java
package myextproc;

import com.github.wrossmorrow.extproc.ExternalProcessorServer;

public class MainClass {
    public void main() {
        final ExternalProcessorServer server = new ExternalProcessorServer()
            .builder()
            .addRequestProcessor(new MyRequestProcessor())
            .addShutdownCallback(myDetailedShutdownMethod)
            .setGracePeriodSeconds(10)
            .start()
            .blockUntilShutdown();
    }
}
```
You can also supply your own server implementation, and use `ExternalProcessor`: 
```java
package myextproc;

import com.github.wrossmorrow.extproc.ExternalProcessor;

ExternalProcessor service = new ExternalProcessor(new MyRequestProcessor());
```
because this contains a gRPC service implementation for `envoy`'s specs. 

### Details

This SDK uses an interface
```java
public interface RequestProcessor {
    public String getName();
    public ProcessingOptions getOptions();
    public void setHealthManager(RequestProcessorHealthManager health);
    public void shutdown();
    public void processRequestHeaders(RequestContext ctx, Map<String, String> headers);
    public void processRequestBody(RequestContext ctx, String body);
    public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers);
    public void processResponseHeaders(RequestContext ctx, Map<String, String> headers);
    public void processResponseBody(RequestContext ctx, String body);
    public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers);
}
```
and a context object `RequestContext` that work together to allow processing of requests and responses. The `ExternalProcessorServer` (or, really, `ExternalProcessor`) handles the gRPC streaming and shared context, parsing the processing phase in the gRPC stream and calling the right `RequestProcessor` implementation method. The header and body messages can be responded to with either a "common" or "immediate" response object (or); the trailer methods can only mutate headers. But that should be opaque to the user of this SDK; the `RequestContext` and `RequestProcessor` are more important. 

### Health Checking

Using `setHealthManager` your processor will get passed a class implementing
```java
public interface RequestProcessorHealthManager {
  public void serving();
  public void notServing();
  public void failed();
}
```
to enable gRPC health checks. Call `failed` if you encounter an unrecoverable, otherwise use `notServing` and `serving` for ephemeral issues (say, loss of connection to a datastore like `redis`). How you manage health checks is otherwise entirely up to you. The default state is `SERVING`, and you may very well need to do nothing. 

### Context Data

The `RequestContext` is initialized with some request data when request headers are received, implying that the `envoy` configuration should _always_ have `processing_mode.request_header_mode: SEND`. Basic request-identifying data (method, path etc) are _only_ available in this phase. As shown in the spec above, this data includes
* the HTTP `Scheme`
* the `Authority` (host)
* the HTTP `Method`
* the URL `Path`
* `envoy`'s `x-request-id` (a UUID)
* _all_ request headers in a case-insensitive `Map<String, String>`
* _all_ response headers in a case-insensitive `Map<String, String>` (when available)
* the request processing stream start time `started`
* an accumulator `duration` _for the time spent in external processing_
* a flag-method `streamComplete` within header, body, and trailer phases to know when phase data is complete
* a flag `ObservabilityMode` reflecting `ProcessingRequest.observability_mode` (when `true`, processor callbacks run for observation and the SDK suppresses `ProcessingResponse` messages)
* `getProtocolConfig()` (`ProtocolConfig`) reflecting `ProcessingRequest.protocol_config` sent by Envoy on the first message of the stream (`requestBodyMode`, `responseBodyMode`, and `sendBodyWithoutWaitingForHeaderResponse`), retained across all phases of that stream
* `getMetadata()` (`Map<String, Map<String, Object>>`) and `getTypedMetadata()` (`Map<String, Any>`) holding dynamic metadata forwarded by Envoy (`metadata_options.forwarding_namespaces`), merged across phases of the stream
* `getAttributes()` (`Map<String, Object>`) holding evaluated Envoy/CEL `request_attributes` and `response_attributes` flattened by attribute name (e.g. `request.protocol`, `xds.route_name`, `response.code`), merged across phases of the stream

This context is carried through every request phase, and passed to the interface methods. In particular, your implementation can store data in memory related to specific requests keyed on the request ID or via another strategy of your choosing. You can supply your own ID, which can be stored in the context, if that suits your needs better, using `setProcessorId`. This can only be set _once_, ideally in request header phase, but can be retrieved throughout the lifetime of a request's processing. 

Your `RequestProcessor` implementation can use whatever storage strategy for your own contextual data you want, but you should make sure to use `requestId` (or your own ID) as a key in case your external processor sees concurrent requests. 

**Custom data.** The context also exposes a custom-data surface via `setValue`/`getValue` (and the typed `getValue(name, Class<T>)`). Custom values set during one phase are available in later phases of the same stream, and are not shared across streams. The backing map is a plain, unsynchronized `HashMap`: the context itself is scoped per stream and is not thread-safe/locked, so a processor relying on context custom data does not need its own synchronization for that map. If your processor keeps its own cross-stream state (say, an in-flight cache), you are responsible for synchronizing it. 

### Forming Responses

We also provide some convenience routines for operating on process phase stream responses, so that users of this SDK need to learn less (preferably nothing) about the specifics of the `envoy` datastructures. The gRPC stream response datastructures are complicated, and our aim is to utilize the `RequestContext` to guard and simplify the construction of responses with a simpler user interface. 

In particular, the methods
```java
RequestContext.continueAndReplace()
RequestContext.cancelRequest(int status, Map<String, String> headers, String body)
```
effectively signal request phase responses "replacing" the request for upstream processing and "responding immediately" (respectively). If you `replace` you won't get further filter requests (the `proto` comments suggest this as a mechanism to convert a `GET` to a `PUT`/`POST`/`PATCH`). Note also that "cancelling" does not necessarily mean request _failure_; just "we know the response now, and don't need to process further". See the [echo](#echo) example for "OK" (200) responses from cancelling. `cancelRequest` builds the immediate response headers solely from the `headers` map passed to it (prior header mutations in the same phase are not included) and defaults `status <= 0` to `400`. After you call `cancelRequest` you should no longer modify request data like headers or the body, as this method prepares the response.

### Dynamic Mode Override

When `allow_mode_override: true` is configured on Envoy's ext_proc filter, processors can adjust the stream's processing mode dynamically during `processRequestHeaders` or `processResponseHeaders` via `ModeOverride` and `RequestContext`:
```java
ctx.overrideMode(
    new ModeOverride()
        .setResponseHeaderMode(ModeOverride.HeaderSendMode.SEND)
        .setRequestBodyMode(ModeOverride.BodySendMode.BUFFERED)
        .setResponseBodyMode(ModeOverride.BodySendMode.NONE)
        .setRequestTrailerMode(ModeOverride.HeaderSendMode.SKIP)
        .setResponseTrailerMode(ModeOverride.HeaderSendMode.SKIP));
```
You can also call `ctx.skipRemainingPhases()` to skip all subsequent phases on the stream, or `ctx.clearModeOverride()` to clear a pending override before returning. `mode_override` is only attached to header phase responses when the request is not cancelled, and is reset after each phase. Note that Envoy's `ProcessingMode` proto has no "keep default" body enum (`NONE` is `0`), so when calling `overrideMode` from `processRequestHeaders`, either set both `requestBodyMode` and `responseBodyMode` explicitly or start from `ctx.defaultModeOverride()` (pre-populated from `ctx.getProtocolConfig()`).

### Dynamic Metadata & Attributes

When Envoy configures `metadata_options.forwarding_namespaces`, `request_attributes`, or `response_attributes` on the `ext_proc` filter, Envoy includes those values on incoming `ProcessingRequest` messages and the SDK merges them across all phases of the stream onto:
* `ctx.getMetadata()` (`Map<String, Map<String, Object>>`) — untyped filter metadata keyed by namespace
* `ctx.getTypedMetadata()` (`Map<String, Any>`) — typed filter metadata keyed by namespace
* `ctx.getAttributes()` (`Map<String, Object>`) — evaluated CEL attributes flattened by attribute name

To emit metadata back to downstream Envoy filters or access logs (also attached to `cancelRequest` immediate responses), set it on the current phase:
```java
ctx.setDynamicMetadata("com.acme.extproc", "tenant", "acme");
Map<String, Object> fields = new LinkedHashMap<String, Object>();
fields.put("risk_score", 0.1);
fields.put("allowed", true);
ctx.setDynamicMetadataNamespace("com.acme.extproc", fields);
```
Use `ctx.clearDynamicMetadata()` to discard pending outbound metadata for the current phase. Note that Envoy ignores returned namespaces unless they are listed in `metadata_options.receiving_namespaces.untyped`, and `protobuf.Struct` stores numeric values as `Double` when read back from `ctx.getMetadata()` or `ctx.getAttributes()`. 

### Modifying Headers

You can add headers to a response with the convenience methods 
```java
appendHeader(String name, String value)
addHeader(String name, String value)
overwriteHeader(String name, String value)
appendHeaders(Map<String, String> headers)
addHeaders(Map<String, String> headers)
overwriteHeaders(Map<String, String> headers)
```
where `append` adds header values if they exist, `add` adds a new value only if the header doesn't exist, and `overwrite` will add or overwrite if a header exists. Header names are lowercased and encoded using Envoy's `raw_value` UTF-8 bytes. The `RequestContext` should keep track of these headers and include them in a formal response to `envoy`. 

Headers can be removed with the
```java
removeHeader(String name)
removeHeaders(List<String> headers)
```
methods, requiring only names of headers to remove. 

### Modifying Bodies

The `RequestContext` exposes body-modification helpers covering both buffered and streamed semantics:
```java
replaceBody(byte[] body)          // replace the whole (buffered) body
replaceBody(String body)          // replace the whole (buffered) body
clearBody()                       // clear the whole (buffered) body
replaceBodyChunk(byte[] body)   // replace a single chunk (buffered: the whole body)
replaceBodyChunk(String body)   // replace a single chunk (buffered: the whole body)
clearBodyChunk()                  // clear a single chunk (buffered: the whole body)
```
Passing `null` to `replaceBody` is a no-op, while passing an empty `byte[0]` or `""` delegates to `clearBody()`. Both `replaceBody` and `clearBody` update the `content-length` header only during header phases (`REQUEST_HEADERS` / `RESPONSE_HEADERS`), not during body phases (`REQUEST_BODY` / `RESPONSE_BODY`).

## Examples

You can run all the examples with 
```shell
./gradlew clean build && docker compose up --build
```
The compose setup runs `envoy` (see `envoy.yaml`), an upstream echo server (see `ghcr.io/wrossmorrow/simple-echo-server`), and several implementations of ExtProcs based on the SDK. These implementations are described below. 

Here is some sample output with the compose setup running: 
```shell
$ curl localhost:8080/hello -s -vvv | jq .
*   Trying 127.0.0.1:8080...
* Connected to localhost (127.0.0.1) port 8080 (#0)
> GET /hello HTTP/1.1
> Host: localhost:8080
> User-Agent: curl/7.85.0
> Accept: */*
> 
* Mark bundle as not supporting multiuse
< HTTP/1.1 200 OK
< date: Mon, 21 Aug 2023 01:55:51 GMT
< content-type: text/plain; charset=utf-8
< x-envoy-upstream-service-time: 0
< x-extproc-request-digest: c9f3c39472d7994b315edded7430515fac5160335ff9b831ed0910cf8d1faa5e
< x-extproc-started-ns: 2023-08-21T01:55:51.513855Z
< x-extproc-finished-ns: 2023-08-21T01:55:51.552927Z
< x-extproc-upstream-duration-ns: 39072000
< x-extproc-response: seen
< x-extproc-duration-ns: echo=2000,dedup=3000,digest=4000,timer=5000,trivial=2000,noop=6000
< server: envoy
< x-request-id: c9792bc4-668a4e99-a89b-da559b1a7e87
< transfer-encoding: chunked
< 
{ [597 bytes data]
* Connection #0 to host localhost left intact
{
  "Datetime": "2023-08-21 01:55:51.541470292 +0000 UTC",
  "Method": "GET",
  "Path": "/hello",
  "Query": {},
  "Headers": {
    "Accept": [
      "*/*"
    ],
    "User-Agent": [
      "curl/7.85.0"
    ],
    "X-Envoy-Expected-Rq-Timeout-Ms": [
      "15000"
    ],
    "X-Extproc-Duration-Ns": [
      "noop=6000,trivial=2000,timer=5000,digest=4000,dedup=3000,echo=2000"
    ],
    "X-Extproc-Request-Digest": [
      "c9f3c39472d7994b315edded7430515fac5160335ff9b831ed0910cf8d1faa5e"
    ],
    "X-Extproc-Request": [
      "seen"
    ],
    "X-Extproc-Started-Ns": [
      "2023-08-21T01:55:51.513855Z"
    ],
    "X-Forwarded-Proto": [
      "http"
    ],
    "X-Request-Id": [
      "c9792bc4-668a4e99-a89b-da559b1a7e87"
    ]
  },
  "Body": "",
  "Duration": 9084
}
```
All examples are defined in `src/main/java/com/github/wrossmorrow/extproc/processors`

### No-op

The `NoOpRequestProcessor` does absolutely nothing, except use the options. Verbose stream and phase logs are emitted, and headers `x-extproc-duration-ns` and `x-extproc-names` are added to the response to the client. These headers are not injected from the processor, but rather the SDK. This is also the default processor if you just boot up the SDK's `jar`.

### Trivial

The `TrivialRequestProcessor` does very little: adds a header to the request sent to an upstream target and a similar header in the response to the client that simply declare the request passed through the processor. 

### Timer

The `TimerRequestProcessor` adds timing headers: `x-extproc-started-ns` (Unix epoch time, in ns, when the request started processing) in the request phase, and `x-extproc-finished-ns` and `x-upstream-duration-ns` (ns elapsed) in the response phases. Note this ExtProc uses data stored in the request context _across phases_, but not _custom_ data. 

### Echo

The `EchoRequestProcessor` is an example of using an ExtProc to _respond_ to a request. If the request path starts with `/echo`, this processor responds directly instead of sending the request on to the upstream target. You can see logs like 
```shell
envoyexternalprocessor-echo-1      | Aug 20, 2023 3:27:40 PM extproc.processors.EchoRequestProcessor processRequestHeaders
envoyexternalprocessor-echo-1      | INFO: EchoRequestProcessor.processRequestBody: /echo/hello responding before upstream
```
and a response like 
```json
{
  "body": "some data here"
}
```
when that occurs. 

### Masker

The `MaskerRequestProcessor` is an example of body modification with `RequestContext.replaceBody` combined with dynamic `mode_override`. Its Envoy filter in `envoy.yaml` enables `allow_mode_override: true` with `request_body_mode: NONE` and `response_body_mode: NONE`; during `processRequestHeaders` and `processResponseHeaders`, the processor inspects `content-type` and opts into `BUFFERED` body processing only for `application/json` payloads while skipping remaining phases otherwise. Masked JSON fields (`maskme` and `mask.me`) are replaced with `****`.

### Digest

The `DigestRequestProcessor` computes a (SHA256) digest of the request, specifically of `<method>:<path>[:body]`, and passes that back to the request client in the response as a header. Such digests are useful when, for example, internally examining duplicate requests (though invariantly changing body bytes, e.g. reordering JSON fields, wouldn't show up as duplication in a hash).

### Dedup

The `DedupRequestProcessor` _uses_ a request digest as above and to reject requests when another request with the _same_ digest is still in flight (i.e., not yet responded to). You can utilize the `?delay=<int>` query param to the proxied echo server to make one "long running" request in one terminal, and another similar request in another terminal and observe the second will have a `409` response. You can use a `PUT`/`POST`/`PATCH` and change the body in the second request and see it pass through. This is an example of "chained" external processors, as this depends on the `DigestRequestProcessor`. 

### Logging

The `LoggingRequestProcessor` logs each phase as it processes the request, using the options' verbose stream/phase logs, and is a minimal example of a processor that observes phases without modifying them.

### Data

The `DataRequestProcessor` stores a custom value (`customId`) in the request context during the request phase and surfaces it to the client in the response via a header, demonstrating the custom-data surface.

### Router

The `RouterRequestProcessor` routes a request to an upstream based on a request header or JSON body, using the custom-data surface and clearing the route cache, demonstrating header/body-driven routing.

### Testing

The `TestingRequestProcessor` is the processor used by the testing harness: it applies a JSON action body across the phases of an exchange (append/add/overwrite/remove headers, clear/replace bodies, cancel), and supports an upstream override via the `x-route-to-upstream` header.

## Conformance

This repository ships an [Envoy conformance harness](conformance) that exercises the SDK against live Envoy proxies across multiple Envoy versions, and tracks `ext_proc` v3 feature coverage.

- Versioned target matrix: [conformance/envoy-versions.json](conformance/envoy-versions.json) (default `v1.40-latest`, supported `v1.37` – `v1.40`, plus `dev` canary).
- Feature coverage plan & matrix: [CONFORMANCE.md](CONFORMANCE.md), [docs/CONFORMANCE_PLAN.md](docs/CONFORMANCE_PLAN.md), [conformance/features.yaml](conformance/features.yaml).
- Purposeful multi-version runs: [`.github/workflows/conformance.yaml`](workflows/conformance.yaml) (manual, weekly schedule, release tags, conformance-path changes).

Quick local entry points:

```sh
just conformance            # fast in-JVM harness tests (no envoy binary required)
just conformance-version v1.40-latest
just conformance-matrix
just conformance-report
just sync-envoy-versions
```

See [CONFORMANCE.md](CONFORMANCE.md) for the version baseline, the generated conformance matrix, and how to run verification.
