package com.github.mizosoft.methanol.internal.extensions;

import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiPredicate;

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

  public void addAll(HttpHeaders headers) {
    addAll(headers.map());
  }

  public void set(String name, String value) {
    set(name, List.of(value));
  }

  public void set(String name, List<String> values) {
    if (!values.isEmpty()) {
      headersMap.put(name, new ArrayList<>(values));
    }
  }

  public void setAll(HttpHeaders headers) {
    headers.map().forEach(this::set);
  }

  public boolean remove(String name) {
    return headersMap.remove(name) != null;
  }

  public boolean removeIf(BiPredicate<String, String> filter) {
    boolean mutated = false;
    for (var iter = headersMap.entrySet().iterator(); iter.hasNext(); ) {
      var entry = iter.next();
      var name = entry.getKey();
      var values = entry.getValue();
      mutated |= values.removeIf(value -> filter.test(name, value));
      if (values.isEmpty()) {
        iter.remove();
      }
    }
    return mutated;
  }

  public void clear() {
    headersMap.clear();
  }

  public HeadersBuilder deepCopy() {
    var copy = new HeadersBuilder();
    copy.addAll(headersMap);
    return copy;
  }

  public HttpHeaders build() {
    return HttpHeaders.of(headersMap, (n, v) -> true);
  }
}
