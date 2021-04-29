package com.github.mizosoft.methanol.internal.extensions;

import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class HeadersBuilder {
  private final Map<String, List<String>> headersMap;

  public HeadersBuilder() {
    headersMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
  }

  public void add(String name, String value) {
    headersMap.computeIfAbsent(name, __ -> new ArrayList<>()).add(value);
  }

  public void add(String name, List<String> values) {
    if (!values.isEmpty()) {
      headersMap.computeIfAbsent(name, __ -> new ArrayList<>()).addAll(values);
    }
  }

  public void addAll(Map<String, List<String>> headers){
    headers.forEach(this::add);
  }

  public void set(String name, String value) {
    set(name, List.of(value));
  }

  public void set(String name, List<String> values) {
    if (!values.isEmpty()) {
      headersMap.put(name, new ArrayList<>(values));
    }
  }

  public boolean remove(String name) {
    return headersMap.remove(name) != null;
  }

  public void clear() {
    headersMap.clear();
  }

  public HeadersBuilder deepCopy() {
    var copy = new HeadersBuilder();
    headersMap.forEach((n, vs) -> copy.headersMap.put(n, new ArrayList<>(vs)));
    return copy;
  }

  public HttpHeaders build() {
    return HttpHeaders.of(headersMap, (n, v) -> true);
  }
}
