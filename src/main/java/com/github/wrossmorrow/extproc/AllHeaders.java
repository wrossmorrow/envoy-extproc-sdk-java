package com.github.wrossmorrow.extproc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-value and raw-value header collection for a processing phase, mirroring the Go and Rust
 * SDKs. Header values are kept in wire order, one entry per occurrence, and are not split on
 * commas. Raw values are retained alongside decoded values for values that are not valid UTF-8.
 */
public class AllHeaders {

  protected final Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
  protected final Map<String, List<byte[]>> rawHeaders = new LinkedHashMap<String, List<byte[]>>();

  public AllHeaders() {}

  public AllHeaders(Map<String, String> headers) {
    if (headers != null) {
      for (Map.Entry<String, String> entry : headers.entrySet()) {
        if (entry.getValue() != null) {
          add(entry.getKey(), entry.getValue());
        }
      }
    }
  }

  /** The first value for the given header name, or null if absent. */
  public String get(String name) {
    List<String> values = headers.get(name);
    if (values == null || values.isEmpty()) {
      return null;
    }
    return values.get(0);
  }

  /** All values for the given header name, in wire order; empty if absent. */
  public List<String> values(String name) {
    List<String> values = headers.get(name);
    if (values == null) {
      return new ArrayList<String>();
    }
    return values;
  }

  /** All raw values for the given header name, in wire order; empty if absent. */
  public List<byte[]> rawValues(String name) {
    List<byte[]> values = rawHeaders.get(name);
    if (values == null) {
      return new ArrayList<byte[]>();
    }
    return values;
  }

  /** Whether the given header name is present. */
  public boolean has(String name) {
    return headers.containsKey(name);
  }

  /** Split a comma-delimited header value into its parts. */
  public List<String> splitList(String name) {
    List<String> values = values(name);
    List<String> result = new ArrayList<String>();
    for (String value : values) {
      for (String part : value.split(",")) {
        result.add(part);
      }
    }
    return result;
  }

  /** Appends a value to the given header name. */
  public void add(String name, String value) {
    if (name == null || value == null) {
      return;
    }
    List<String> values = headers.get(name);
    if (values == null) {
      values = new ArrayList<String>();
      headers.put(name, values);
    }
    values.add(value);
  }

  /** Sets the given header name to a single value. */
  public void set(String name, String value) {
    List<String> values = new ArrayList<String>();
    values.add(value);
    headers.put(name, values);
  }

  /** Removes the given header name. */
  public void remove(String name) {
    if (name == null) {
      return;
    }
    headers.remove(name);
    rawHeaders.remove(name);
  }

  /** Number of distinct header names present. */
  public int size() {
    return headers.size();
  }

  /** Clears all headers. */
  public void clear() {
    headers.clear();
    rawHeaders.clear();
  }
}
