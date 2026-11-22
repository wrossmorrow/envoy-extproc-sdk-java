package com.github.wrossmorrow.extproc.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.netty.shaded.io.netty.bootstrap.ServerBootstrap;
import io.grpc.netty.shaded.io.netty.buffer.ByteBuf;
import io.grpc.netty.shaded.io.netty.buffer.Unpooled;
import io.grpc.netty.shaded.io.netty.channel.Channel;
import io.grpc.netty.shaded.io.netty.channel.ChannelFutureListener;
import io.grpc.netty.shaded.io.netty.channel.ChannelHandlerContext;
import io.grpc.netty.shaded.io.netty.channel.ChannelInitializer;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.SimpleChannelInboundHandler;
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.socket.SocketChannel;
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioServerSocketChannel;
import io.grpc.netty.shaded.io.netty.handler.codec.http.DefaultHttpContent;
import io.grpc.netty.shaded.io.netty.handler.codec.http.DefaultHttpHeaders;
import io.grpc.netty.shaded.io.netty.handler.codec.http.DefaultHttpResponse;
import io.grpc.netty.shaded.io.netty.handler.codec.http.DefaultLastHttpContent;
import io.grpc.netty.shaded.io.netty.handler.codec.http.FullHttpRequest;
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpHeaderNames;
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpHeaderValues;
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpHeaders;
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpObjectAggregator;
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpResponseStatus;
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpServerCodec;
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpUtil;
import io.grpc.netty.shaded.io.netty.handler.codec.http.HttpVersion;
import io.grpc.netty.shaded.io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.grpc.netty.shaded.io.netty.handler.codec.http2.Http2CodecUtil;
import io.grpc.netty.shaded.io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.grpc.netty.shaded.io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.grpc.netty.shaded.io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.grpc.netty.shaded.io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.grpc.netty.shaded.io.netty.util.AsciiString;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In-process HTTP/1.1 + h2c upstream echo/control server used as Envoy's backend during conformance
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

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String name;
  private EventLoopGroup bossGroup;
  private EventLoopGroup workerGroup;
  private Channel serverChannel;
  private int port;

  private UpstreamServer(String name) {
    this.name = name;
  }

  public static UpstreamServer start(String name) throws Exception {
    final UpstreamServer upstream = new UpstreamServer(name);
    upstream.bossGroup = new NioEventLoopGroup(1);
    upstream.workerGroup = new NioEventLoopGroup(2);
    ServerBootstrap bootstrap =
        new ServerBootstrap()
            .group(upstream.bossGroup, upstream.workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel ch) {
                    HttpServerCodec sourceCodec = new HttpServerCodec();
                    HttpServerUpgradeHandler.UpgradeCodecFactory upgradeFactory =
                        new HttpServerUpgradeHandler.UpgradeCodecFactory() {
                          @Override
                          public HttpServerUpgradeHandler.UpgradeCodec newUpgradeCodec(
                              CharSequence protocol) {
                            if (AsciiString.contentEquals(
                                Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
                              return new Http2ServerUpgradeCodec(
                                  Http2FrameCodecBuilder.forServer().build(),
                                  new Http2MultiplexHandler(
                                      new Http2StreamInitializer(upstream, "HTTP/2.0")));
                            }
                            return null;
                          }
                        };
                    HttpServerUpgradeHandler upgradeHandler =
                        new HttpServerUpgradeHandler(sourceCodec, upgradeFactory, 1024 * 1024);
                    CleartextHttp2ServerUpgradeHandler cleartextHandler =
                        new CleartextHttp2ServerUpgradeHandler(
                            sourceCodec,
                            upgradeHandler,
                            new ChannelInitializer<Channel>() {
                              @Override
                              protected void initChannel(Channel h2ch) {
                                h2ch.pipeline()
                                    .addLast(
                                        Http2FrameCodecBuilder.forServer().build(),
                                        new Http2MultiplexHandler(
                                            new Http2StreamInitializer(upstream, "HTTP/2.0")));
                              }
                            });
                    ch.pipeline()
                        .addLast(
                            cleartextHandler,
                            new HttpObjectAggregator(1024 * 1024),
                            new UpstreamHandler(upstream, "HTTP/1.1"));
                  }
                });

    Channel channel = bootstrap.bind(new InetSocketAddress("0.0.0.0", 0)).sync().channel();
    upstream.serverChannel = channel;
    upstream.port = ((InetSocketAddress) channel.localAddress()).getPort();
    return upstream;
  }

  private static final class Http2StreamInitializer extends ChannelInitializer<Channel> {
    private final UpstreamServer upstream;
    private final String proto;

    Http2StreamInitializer(UpstreamServer upstream, String proto) {
      this.upstream = upstream;
      this.proto = proto;
    }

    @Override
    protected void initChannel(Channel ch) {
      ch.pipeline()
          .addLast(
              new Http2StreamFrameToHttpObjectCodec(true),
              new HttpObjectAggregator(1024 * 1024),
              new UpstreamHandler(upstream, proto));
    }
  }

  private static final class UpstreamHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final UpstreamServer upstream;
    private final String proto;

    UpstreamHandler(UpstreamServer upstream, String proto) {
      this.upstream = upstream;
      this.proto = proto;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
      upstream.handle(ctx, req, proto);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      ctx.close();
    }
  }

  private void handle(ChannelHandlerContext ctx, FullHttpRequest req, String proto)
      throws Exception {
    ByteBuf content = req.content();
    byte[] bodyBytes = new byte[content.readableBytes()];
    content.getBytes(content.readerIndex(), bodyBytes);

    Map<String, List<String>> requestHeaders = toMultiMap(req.headers());
    Map<String, List<String>> requestTrailers = toMultiMap(req.trailingHeaders());

    String delayStr = first(requestHeaders, "x-upstream-delay-ms");
    if (delayStr != null) {
      try {
        int ms = Integer.parseInt(delayStr);
        if (ms > 0) {
          Thread.sleep(Math.min(ms, 2000));
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
    if (requestHeaders.containsKey("x-upstream-raw-body")) {
      String rawBody = first(requestHeaders, "x-upstream-raw-body");
      responseBody = rawBody == null ? new byte[0] : rawBody.getBytes(StandardCharsets.UTF_8);
      String ct = first(requestHeaders, "x-upstream-content-type");
      if (ct != null) {
        contentType = ct;
      }
    } else {
      URI uri = URI.create(req.uri());
      UpstreamEcho echo = new UpstreamEcho();
      echo.server = name;
      echo.proto =
          first(requestHeaders, "x-conformance-proto") != null
              ? first(requestHeaders, "x-conformance-proto")
              : proto;
      echo.host =
          first(requestHeaders, "host") != null
              ? first(requestHeaders, "host")
              : (first(requestHeaders, "x-conformance-host") != null
                  ? first(requestHeaders, "x-conformance-host")
                  : "");
      echo.method = req.method().name();
      echo.path = uri.getRawPath();
      echo.rawQuery = uri.getRawQuery();
      echo.headers = requestHeaders;
      echo.trailers = requestTrailers;
      echo.body = new String(bodyBytes, StandardCharsets.UTF_8);
      echo.bodyBytes = bodyBytes.length;
      responseBody = MAPPER.writeValueAsBytes(echo);
      contentType = "application/json";
    }

    Map<String, String> respTrailers = new LinkedHashMap<String, String>();
    for (Map.Entry<String, List<String>> entry : requestHeaders.entrySet()) {
      String lower = entry.getKey().toLowerCase(Locale.ROOT);
      if (lower.startsWith("x-upstream-trailer-") && !entry.getValue().isEmpty()) {
        String tName = lower.substring("x-upstream-trailer-".length());
        if (!tName.isEmpty()) {
          respTrailers.put(tName, entry.getValue().get(0));
        }
      }
    }

    int chunks = 1;
    String chunksStr = first(requestHeaders, "x-upstream-chunks");
    if (chunksStr != null) {
      try {
        int n = Integer.parseInt(chunksStr);
        if (n > 1) {
          chunks = n;
        }
      } catch (NumberFormatException ignored) {
      }
    }

    DefaultHttpResponse resp =
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(statusCode));
    resp.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
    String caseId = first(requestHeaders, ConformanceProcessor.CASE_HEADER);
    if (caseId != null) {
      resp.headers().set(ConformanceProcessor.CASE_HEADER, caseId);
    }
    boolean isHttp2 = "HTTP/2.0".equals(proto);
    if (!isHttp2) {
      if (!respTrailers.isEmpty()) {
        for (String tName : respTrailers.keySet()) {
          resp.headers().add(HttpHeaderNames.TRAILER, tName);
        }
        resp.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
      } else if (chunks > 1) {
        resp.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
      } else if (statusCode != 204 && statusCode != 304) {
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, responseBody.length);
      }
    } else if (respTrailers.isEmpty() && chunks <= 1 && statusCode != 204 && statusCode != 304) {
      resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, responseBody.length);
    }

    boolean keepAlive = HttpUtil.isKeepAlive(req) && !"HTTP/2.0".equals(proto);
    if (keepAlive) {
      resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
    }

    ctx.write(resp);

    if (chunks > 1 && responseBody.length > 0) {
      int chunkSize = (responseBody.length + chunks - 1) / chunks;
      for (int offset = 0; offset < responseBody.length; offset += chunkSize) {
        int end = Math.min(offset + chunkSize, responseBody.length);
        ByteBuf slice = Unpooled.copiedBuffer(responseBody, offset, end - offset);
        ctx.write(new DefaultHttpContent(slice));
      }
    } else if (responseBody.length > 0) {
      ctx.write(new DefaultHttpContent(Unpooled.wrappedBuffer(responseBody)));
    }

    HttpHeaders trailing = new DefaultHttpHeaders();
    for (Map.Entry<String, String> entry : respTrailers.entrySet()) {
      trailing.set(entry.getKey(), entry.getValue());
    }
    DefaultLastHttpContent last = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER, trailing);
    if (keepAlive || "HTTP/2.0".equals(proto)) {
      ctx.writeAndFlush(last);
    } else {
      ctx.writeAndFlush(last).addListener(ChannelFutureListener.CLOSE);
    }
  }

  private static Map<String, List<String>> toMultiMap(HttpHeaders headers) {
    Map<String, List<String>> map = new LinkedHashMap<String, List<String>>();
    if (headers == null) {
      return map;
    }
    for (Map.Entry<String, String> entry : headers) {
      String key = entry.getKey().toLowerCase(Locale.ROOT);
      List<String> list = map.get(key);
      if (list == null) {
        list = new ArrayList<String>();
        map.put(key, list);
      }
      list.add(entry.getValue());
    }
    return map;
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
    if (serverChannel != null) {
      serverChannel.close().syncUninterruptibly();
    }
    if (bossGroup != null) {
      bossGroup.shutdownGracefully().syncUninterruptibly();
    }
    if (workerGroup != null) {
      workerGroup.shutdownGracefully().syncUninterruptibly();
    }
  }
}
