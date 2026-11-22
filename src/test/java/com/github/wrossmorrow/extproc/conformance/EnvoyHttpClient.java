package com.github.wrossmorrow.extproc.conformance;

import io.grpc.netty.shaded.io.netty.bootstrap.Bootstrap;
import io.grpc.netty.shaded.io.netty.buffer.ByteBuf;
import io.grpc.netty.shaded.io.netty.buffer.Unpooled;
import io.grpc.netty.shaded.io.netty.channel.Channel;
import io.grpc.netty.shaded.io.netty.channel.ChannelHandlerContext;
import io.grpc.netty.shaded.io.netty.channel.ChannelInitializer;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.SimpleChannelInboundHandler;
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.socket.SocketChannel;
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel;
import io.grpc.netty.shaded.io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.grpc.netty.shaded.io.netty.handler.codec.http.FullHttpResponse;
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpHeaderNames;
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpMethod;
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpObjectAggregator;
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpVersion;
import io.grpc.netty.shaded.io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.grpc.netty.shaded.io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.grpc.netty.shaded.io.netty.handler.codec.http2.Http2StreamChannel;
import io.grpc.netty.shaded.io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.grpc.netty.shaded.io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** HTTP/1.1, chunked+trailers, and h2c client used by conformance scenarios against Envoy. */
public class EnvoyHttpClient {

  public static final class Response {
    public int status = 0;
    public Map<String, String> headers = new HashMap<String, String>();
    public Map<String, List<String>> allHeaders = new LinkedHashMap<String, List<String>>();
    public Map<String, String> trailers = new LinkedHashMap<String, String>();
    public String body = "";
  }

  private final String baseUrl;
  private final int timeoutMs;

  public EnvoyHttpClient(String baseUrl, int timeoutMs) {
    this.baseUrl = baseUrl;
    this.timeoutMs = timeoutMs;
  }

  public Response send(String method, String path, Map<String, String> headers, String body) {
    Response response = new Response();
    try {
      URL url = new URL(baseUrl + path);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(timeoutMs);
      conn.setReadTimeout(timeoutMs);
      conn.setRequestMethod(method);
      conn.setInstanceFollowRedirects(false);
      if (headers != null) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
          if (entry.getKey() != null && entry.getValue() != null) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
          }
        }
      }
      if (body != null) {
        conn.setDoOutput(true);
        if (headers == null || !headers.containsKey("Content-Type")) {
          conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
        }
        OutputStream out = conn.getOutputStream();
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.close();
      }
      response.status = conn.getResponseCode();
      Map<String, String> responseHeaders = new HashMap<String, String>();
      Map<String, List<String>> allResponseHeaders = new LinkedHashMap<String, List<String>>();
      Map<String, List<String>> headerFields = conn.getHeaderFields();
      if (headerFields != null) {
        for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
          if (entry.getKey() == null) {
            continue;
          }
          List<String> values = entry.getValue();
          if (values == null || values.isEmpty()) {
            continue;
          }
          String lower = entry.getKey().toLowerCase(Locale.ROOT);
          responseHeaders.put(lower, values.get(values.size() - 1));
          allResponseHeaders.put(lower, new ArrayList<String>(values));
        }
      }
      response.headers = responseHeaders;
      response.allHeaders = allResponseHeaders;
      InputStream in = response.status < 400 ? conn.getInputStream() : conn.getErrorStream();
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      if (in != null) {
        while ((n = in.read(buf)) != -1) {
          bos.write(buf, 0, n);
        }
      }
      response.body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
      conn.disconnect();
      return response;
    } catch (Exception e) {
      response.status = -1;
      response.body = e.getMessage();
      return response;
    }
  }

  public Response sendH2c(String method, String path, Map<String, String> headers, String body) {
    return sendH2c(method, path, headers, body, null);
  }

  public Response sendH2c(
      String method,
      String path,
      Map<String, String> headers,
      String body,
      Map<String, String> trailers) {
    Response response = new Response();
    EventLoopGroup group = new NioEventLoopGroup(1);
    try {
      URI uri = URI.create(baseUrl + path);
      Bootstrap b =
          new Bootstrap()
              .group(group)
              .channel(NioSocketChannel.class)
              .handler(
                  new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                      ch.pipeline()
                          .addLast(
                              Http2FrameCodecBuilder.forClient().build(),
                              new Http2MultiplexHandler(
                                  new SimpleChannelInboundHandler<Object>() {
                                    @Override
                                    protected void channelRead0(
                                        ChannelHandlerContext ctx, Object msg) {}
                                  }));
                    }
                  });
      Channel parent = b.connect(uri.getHost(), uri.getPort()).sync().channel();
      final CompletableFuture<FullHttpResponse> future = new CompletableFuture<FullHttpResponse>();
      Http2StreamChannel stream =
          new Http2StreamChannelBootstrap(parent)
              .handler(
                  new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                      ch.pipeline()
                          .addLast(
                              new Http2StreamFrameToHttpObjectCodec(false),
                              new HttpObjectAggregator(1024 * 1024),
                              new SimpleChannelInboundHandler<FullHttpResponse>() {
                                @Override
                                protected void channelRead0(
                                    ChannelHandlerContext ctx, FullHttpResponse msg) {
                                  future.complete(msg.retain());
                                }

                                @Override
                                public void exceptionCaught(
                                    ChannelHandlerContext ctx, Throwable cause) {
                                  future.completeExceptionally(cause);
                                }
                              });
                    }
                  })
              .open()
              .sync()
              .getNow();

      byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
      ByteBuf buf = Unpooled.wrappedBuffer(payload);
      String fullPath =
          uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
      DefaultFullHttpRequest req =
          new DefaultFullHttpRequest(
              HttpVersion.HTTP_1_1, HttpMethod.valueOf(method), fullPath, buf);
      req.headers().set(HttpHeaderNames.HOST, uri.getHost() + ":" + uri.getPort());
      req.headers().set("x-http2-scheme", "http");
      if (headers != null) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
          if (e.getKey() != null && e.getValue() != null) {
            req.headers().set(e.getKey(), e.getValue());
          }
        }
      }
      boolean hasTrailers = trailers != null && !trailers.isEmpty();
      if (hasTrailers) {
        for (Map.Entry<String, String> t : trailers.entrySet()) {
          if (t.getKey() != null && t.getValue() != null) {
            req.trailingHeaders().set(t.getKey(), t.getValue());
          }
        }
      }
      if (payload.length > 0 && !hasTrailers) {
        req.headers().set(HttpHeaderNames.CONTENT_LENGTH, payload.length);
      }
      stream.writeAndFlush(req).sync();

      FullHttpResponse httpResp = future.get(timeoutMs, TimeUnit.MILLISECONDS);
      try {
        response.status = httpResp.status().code();
        for (Map.Entry<String, String> entry : httpResp.headers()) {
          String k = entry.getKey().toLowerCase(Locale.ROOT);
          response.headers.put(k, entry.getValue());
          response
              .allHeaders
              .computeIfAbsent(k, ignored -> new ArrayList<String>())
              .add(entry.getValue());
        }
        for (Map.Entry<String, String> entry : httpResp.trailingHeaders()) {
          response.trailers.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
        byte[] respBytes = new byte[httpResp.content().readableBytes()];
        httpResp.content().getBytes(httpResp.content().readerIndex(), respBytes);
        response.body = new String(respBytes, StandardCharsets.UTF_8);
      } finally {
        httpResp.release();
        parent.close().syncUninterruptibly();
      }
      return response;
    } catch (Exception e) {
      response.status = -1;
      response.body = e.getMessage();
      return response;
    } finally {
      group.shutdownGracefully().syncUninterruptibly();
    }
  }

  /**
   * Sends an HTTP/1.1 chunked request with optional request trailers and parses chunked trailers.
   */
  public Response sendChunked(
      String method,
      String path,
      Map<String, String> headers,
      List<String> chunks,
      Map<String, String> trailers) {
    Response response = new Response();
    try {
      URI uri = URI.create(baseUrl + path);
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), timeoutMs);
        socket.setSoTimeout(timeoutMs);
        OutputStream out = socket.getOutputStream();
        StringBuilder req = new StringBuilder();
        req.append(method).append(' ').append(uri.getRawPath());
        if (uri.getRawQuery() != null) {
          req.append('?').append(uri.getRawQuery());
        }
        req.append(" HTTP/1.1\r\n");
        req.append("Host: ").append(uri.getHost()).append(':').append(uri.getPort()).append("\r\n");
        req.append("Transfer-Encoding: chunked\r\n");
        req.append("Connection: close\r\n");
        if (headers != null) {
          for (Map.Entry<String, String> e : headers.entrySet()) {
            req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
          }
        }
        if (trailers != null && !trailers.isEmpty()) {
          req.append("Trailer: ").append(String.join(", ", trailers.keySet())).append("\r\n");
        }
        req.append("\r\n");
        out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
        if (chunks != null) {
          for (String chunk : chunks) {
            byte[] bytes = chunk.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0) {
              continue;
            }
            out.write(
                (Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
          }
        }
        out.write("0\r\n".getBytes(StandardCharsets.US_ASCII));
        if (trailers != null) {
          for (Map.Entry<String, String> t : trailers.entrySet()) {
            out.write(
                (t.getKey() + ": " + t.getValue() + "\r\n").getBytes(StandardCharsets.US_ASCII));
          }
        }
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();

        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        String statusLine = readLine(in);
        if (statusLine == null) {
          response.status = -1;
          return response;
        }
        String[] parts = statusLine.split(" ");
        response.status = parts.length >= 2 ? Integer.parseInt(parts[1].trim()) : -1;

        while (true) {
          String line = readLine(in);
          if (line == null || line.isEmpty()) {
            break;
          }
          int colon = line.indexOf(':');
          if (colon > 0) {
            String k = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String v = line.substring(colon + 1).trim();
            response.headers.put(k, v);
            response.allHeaders.computeIfAbsent(k, ignored -> new ArrayList<String>()).add(v);
          }
        }

        String te = response.headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
          ByteArrayOutputStream bodyOut = new ByteArrayOutputStream();
          while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
              break;
            }
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            int size = Integer.parseInt(hex, 16);
            if (size == 0) {
              while (true) {
                String trailerLine = readLine(in);
                if (trailerLine == null || trailerLine.isEmpty()) {
                  break;
                }
                int colon = trailerLine.indexOf(':');
                if (colon > 0) {
                  String k = trailerLine.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                  String v = trailerLine.substring(colon + 1).trim();
                  response.trailers.put(k, v);
                }
              }
              break;
            }
            byte[] buf = new byte[size];
            int read = 0;
            while (read < size) {
              int r = in.read(buf, read, size - read);
              if (r < 0) {
                break;
              }
              read += r;
            }
            bodyOut.write(buf, 0, read);
            readLine(in); // trailing CRLF
          }
          response.body = new String(bodyOut.toByteArray(), StandardCharsets.UTF_8);
        } else {
          ByteArrayOutputStream bodyOut = new ByteArrayOutputStream();
          byte[] buf = new byte[4096];
          int r;
          while ((r = in.read(buf)) != -1) {
            bodyOut.write(buf, 0, r);
          }
          response.body = new String(bodyOut.toByteArray(), StandardCharsets.UTF_8);
        }
      }
      return response;
    } catch (Exception e) {
      response.status = -1;
      response.body = e.getMessage();
      return response;
    }
  }

  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1) {
      if (b == '\n') {
        byte[] bytes = out.toByteArray();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') {
          len--;
        }
        return new String(bytes, 0, len, StandardCharsets.US_ASCII);
      }
      out.write(b);
    }
    return out.size() == 0 ? null : new String(out.toByteArray(), StandardCharsets.US_ASCII);
  }
}
