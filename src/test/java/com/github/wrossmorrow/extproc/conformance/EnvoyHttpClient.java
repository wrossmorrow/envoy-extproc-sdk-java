package com.github.wrossmorrow.extproc.conformance;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal HTTP/1.1 client used by conformance scenarios against the envoy proxy. */
public class EnvoyHttpClient {

  public static final class Response {
    public int status = 0;
    public Map<String, String> headers = new HashMap<String, String>();
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
      for (Map.Entry<String, String> entry : headers.entrySet()) {
        if (entry.getKey() != null && entry.getValue() != null) {
          conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
      }
      if (body != null) {
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
        java.io.OutputStream out = conn.getOutputStream();
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.close();
      }
      response.status = conn.getResponseCode();
      Map<String, String> responseHeaders = new HashMap<String, String>();
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
          responseHeaders.put(entry.getKey(), values.get(values.size() - 1));
        }
      }
      response.headers = responseHeaders;
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
}
