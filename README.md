
# An Envoy ExternalProcessor SDK (java)

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

import extproc.ExternalProcessorServer;

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

import extproc.ExternalProcessor;

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
* a flag-method `streamComplete` within header and body phases to know when request phase data is complete

This context is carried through every request phase, and passed to the interface methods. In particular, your implementation can store data in memory related to specific requests keyed on the request ID or via another strategy of your choosing. You can supply your own ID, which can be stored in the context, if that suits your needs better, using `setProcessorId`. This can only be set _once_, ideally in request header phase, but can be retrieved throughout the lifetime of a request's processing. 

Your `RequestProcessor` implementation can use whatever storage strategy for your own contextual data you want, but you should make sure to use `requestId` (or your own ID) as a key in case your external processor sees concurrent requests. 

### Forming Responses

We also provide some convenience routines for operating on process phase stream responses, so that users of this SDK need to learn less (preferably nothing) about the specifics of the `envoy` datastructures. The gRPC stream response datastructures are complicated, and our aim is to utilize the `RequestContext` to guard and simplify the construction of responses with a simpler user interface. 

In particular, the methods
```go
RequestContext.continueRequest()
RequestContext.continueAndReplace()
RequestContext.cancelRequest(...)
```
effectively prepare request phase responses for "continuing", "replacing" the request for upstream processing, and "responding immediately" (respectively). Note that "cancelling" does not necessarily mean request failure; just "we know the response now, and don't need to process further". See the [echo](#echo) example for "OK" (200) responses from cancelling. After you call one of these methods you can no longer modify request data like headers or the body. 

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
where `append` adds header values if they exist, `add` adds a new value only if the header doesn't exist, and `overwrite` will add or overwrite if a header exists. The `RequestContext` should keep track of these headers and include them in a formal response to `envoy`. 

Headers can be removed with the
```java
removeHeader(String name)
removeHeaders(List<String> headers)
```
methods, requiring only names of headers to remove. 

### Modifying Bodies

Two methods help modify bodies: 
```java
replaceBodyChunk(byte[] | String body)
clearBodyChunk()
```
These are the two options currently available in `envoy` ExtProcs: replace a chunk and clear the entire chunk. Note that with buffered bodies the "chunks" should be the entire body. See the [masker](#masker) example discussed below. 

## Examples

You can run all the examples with 
```shell
./gradlew clean build && docker-compose up --build
```
The compose setup runs `envoy` (see `examples/envoy.yaml`), a mock echo server (see `_mocks/echo`), and several implementations of ExtProcs based on the SDK. These implementations are described below. 

Here is some sample output with the compose setup running: 
```shell
$ curl localhost:8080/?delay=3 -s -vvv | jq .
*   Trying 127.0.0.1:8080...
* Connected to localhost (127.0.0.1) port 8080 (#0)
> GET /?delay=3 HTTP/1.1
> Host: localhost:8080
> User-Agent: curl/7.85.0
> Accept: */*
> 
* Mark bundle as not supporting multiuse
< HTTP/1.1 200 OK
< date: Mon, 21 Aug 2023 00:55:02 GMT
< content-type: text/plain; charset=utf-8
< x-envoy-upstream-service-time: 3010
< x-extproc-started: 2023-08-21T00:54:59.812084Z
< x-extproc-finished: 2023-08-21T00:55:02.859951Z
< x-extproc-upstream-duration-ns: 3047867000
< x-extproc-response-seen: true
< x-extproc-duration-ns: echo=3000,timer=3000,trivial=3000,noop=4000
< server: envoy
< x-request-id: 600346f9-f76b-442b-8d31-aa77383a609b
< transfer-encoding: chunked
< 
{ [492 bytes data]
* Connection #0 to host localhost left intact
{
  "Datetime": "2023-08-21 00:54:59.831847755 +0000 UTC",
  "Method": "GET",
  "Path": "/",
  "Query": {
    "delay": [
      "3"
    ]
  },
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
      "noop=4000,trivial=3000,timer=3000,echo=3000"
    ],
    "X-Extproc-Request-Seen": [
      "true"
    ],
    "X-Extproc-Started": [
      "2023-08-21T00:54:59.812084Z"
    ],
    "X-Forwarded-Proto": [
      "http"
    ],
    "X-Request-Id": [
      "600346f9-f76b-442b-8d31-aa77383a609b"
    ]
  },
  "Body": "",
  "Duration": 3002055210
}
```
All examples are defined in `src/main/java/extproc/processors`

### No-op

The `NoOpRequestProcessor` does absolutely nothing, except use the options. Verbose stream and phase logs are emitted, and headers `x-extproc-duration-ns` and `x-extproc-processors` are added to the response to the client. These headers are not injected from the processor, but rather the SDK. This is also the default processor if you just boot up the SDK's `jar`.

### Trivial

The `TrivialRequestProcessor` does very little: adds a header to the request sent to an upstream target and a similar header in the response to the client that simply declare the request passed through the processor. 

### Timer

The `TimerRequestProcessor` adds timing headers: one to the request sent to the upstream with the ISO time when the request started processing, and similar started, finished, and duration (ns) headers to the response sent to the client. Note this ExtProc uses data stored in the request context _across phases_, but not _custom_ data. 

### Echo

The `EchoRequestProcessor` is an example of using an ExtProc to _respond_ to a request. If the request path starts with `/echo`, this processor responds directly instead of sending the request on to the upstream target. You can see logs like 
```shell
envoyexternalprocessor-echo-1      | Aug 20, 2023 3:27:40 PM extproc.processors.EchoRequestProcessor processRequestHeaders
envoyexternalprocessor-echo-1      | INFO: EchoRequestProcessor.processRequestBody: /echo/hello responding before upstream
```
and a JSON response like 
```json
{
  "path": "/echo/hello",
  "body": "some data here"
}
```
when that occurs. 

### Digest

The `DigestRequestProcessor` computes a (SHA256) digest of the request, specifically of `<method>:<path>[:body]`, and passes that back to the request client in the response as a header. Such digests are useful when, for example, internally examining duplicate requests (though invariantly changing body bytes, e.g. reordering JSON fields, wouldn't show up as duplication in a hash).

### Dedup

The `DedupRequestProcessor` _uses_ a request digest as above and to reject requests when another request with the _same_ digest is still in flight (i.e., not yet responded to). You can utilize the ?delay=<int> query param to the proxied echo server to make one "long running" request in one terminal, and another similar request in another terminal and observe the second will have a `409` response. You can use a `PUT`/`POST`/`PATCH` and change the body in the second request and see it pass through. This is an example of "chained" external processors, as this depends on the `DigestRequestProcessor`. 
