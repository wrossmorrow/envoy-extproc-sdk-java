package com.github.wrossmorrow.extproc.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-process HTTP/1.1 upstream echo/control server used as Envoy's backend during conformance
 * tests, mirroring the Go SDK's {@code conformance/upstream.go}.
 */
public class UpstreamServer {

  public static final class UpstreamEcho {
    public String server;
    public String proto;
    public String host;
    public String method;
    public String path;
    public String rawQuery;
    public Map<String, List<String>> headers = new HashMap<String, List<String>>();
    public Map<String, List<String>> trailers = new HashMap<String, List<String>>();
    public String body;
    public int bodyBytes;
  }

  private HttpServer server;
  private int port;
  private String name = "primary";

  private UpstreamServer() {}

  public static UpstreamServer start(String name) throws Exception {
    UpstreamServer upstream = new UpstreamServer();
    upstream.name = name;
    ServerSocket socket = new ServerSocket(0);
    int port = socket.getLocalPort();
    socket.close();
    upstream.port = port;
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 64);
    server.createContext(
        "/",
        new HttpHandler() {
          @Override
          public void handle(HttpExchange exchange) throws IOException {
            try {
              upstream.handle(exchange);
            } catch (IOException e) {
              throw e;
            }
          }
        });
    server.start();
    upstream.server = server;
    return upstream;
  }

  private void handle(HttpExchange exchange) throws IOException {
    InputStream in = exchange.getRequestBody();
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) != -1) {
      bos.write(buf, 0, n);
    }
    byte[] bodyBytes = bos.toByteArray();

    Map<String, List<String>> requestHeaders = new HashMap<String, List<String>>();
    for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
      requestHeaders.put(entry.getKey(), entry.getValue());
    }
    List<String> delayValues = requestHeaders.get("x-upstream-delay-ms");
    String delayStr = delayValues == null || delayValues.isEmpty() ? null : delayValues.get(0);
    if (delayStr != null) {
      try {
        int ms = Integer.parseInt(delayStr);
        if (ms > 0) {
          try {
            Thread.sleep(Math.min(ms, 2000));
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
        }
      } catch (NumberFormatException ignored) {
      }
    }
    int statusCode = 200;
    String statusStr = first(requestHeaders, "x-upstream-status");
    if (statusStr != null) {
      try {
        int code = Integer.parseInt(statusStr);
        if (code >= 100 && code <= 599) {
          statusCode = code;
        }
      } catch (NumberFormatException ignored) {
      }
    }

    byte[] responseBody;
    String contentType = "text/plain; charset=utf-8";
    if (first(requestHeaders, "x-upstream-raw-body") != null) {
      List<String> rawBodyValues = requestHeaders.get("x-upstream-raw-body");
      responseBody =
          rawBodyValues == null
              ? new byte[0]
              : rawBodyValues.get(0).getBytes(StandardCharsets.UTF_8);
      String ct = first(requestHeaders, "x-upstream-content-type");
      if (ct != null) {
        contentType = ct;
      }
    } else {
      Map<String, Object> echo = new HashMap<String, Object>();
      echo.put("server", name);
      echo.put(
          "proto",
          first(requestHeaders, "x-conformance-proto") != null
              ? first(requestHeaders, "x-conformance-proto")
              : "HTTP/1.1");
      echo.put(
          "host",
          first(requestHeaders, "x-conformance-host") != null
              ? first(requestHeaders, "x-conformance-host")
              : "");
      echo.put("method", exchange.getRequestMethod());
      echo.put("path", exchange.getRequestURI().getPath());
      echo.put("rawQuery", exchange.getRequestURI().getRawQuery());
      echo.put("headers", requestHeaders);
      echo.put("trailers", new HashMap<String, List<String>>());
      echo.put("body", new String(bodyBytes, StandardCharsets.UTF_8));
      echo.put("bodyBytes", bodyBytes.length);
      try {
        ObjectMapper mapper = new ObjectMapper();
        responseBody = mapper.writeValueAsBytes(echo);
        contentType = "application/json";
      } catch (Exception e) {
        responseBody = bodyBytes;
        contentType = "application/octet-stream";
      }
    }

    byte[] finalResponse = responseBody;
    int contentLength = finalResponse.length;
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(statusCode, contentLength);
    OutputStream out = exchange.getResponseBody();
    out.write(finalResponse);
    out.close();
  }

  private static String first(Map<String, List<String>> headers, String key) {
    List<String> values = headers.get(key);
    if (values == null || values.isEmpty()) {
      return null;
    }
    return values.get(0);
  }

  public int port() {
    return port;
  }

  public String url() {
    return "http://127.0.0.1:" + port;
  }

  public void close() throws Exception {
    if (server != null) {
      server.stop(2);
    }
  }
}
