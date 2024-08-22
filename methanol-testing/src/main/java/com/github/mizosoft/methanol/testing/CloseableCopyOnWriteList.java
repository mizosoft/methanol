package com.github.mizosoft.methanol.testing;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A minimal thread-safe list that create a new copy of the array whenever a new item is added. The
 * list can be closed and a snapshot of added items will be returned, after which no further items
 * can be added.
 */
public final class CloseableCopyOnWriteList<T> implements Iterable<T> {
  private final AtomicReference<Object[]> items = new AtomicReference<>();
  private final Object[] closedSentinel;

  CloseableCopyOnWriteList() {
    items.set(new Object[0]);
    closedSentinel = new Object[0];
  }

  public boolean add(T item) {
    while (true) {
      var currentItems = items.get();
      if (currentItems == closedSentinel) {
        return false;
      }

      var updatedItems = Arrays.copyOf(currentItems, currentItems.length + 1);
      updatedItems[updatedItems.length - 1] = item;
      if (items.compareAndSet(currentItems, updatedItems)) {
        return true;
      }
    }
  }

  @SuppressWarnings("unchecked")
  public T get(int index) {
    var currentItems = items.get();
    Objects.checkIndex(index, currentItems.length);
    return (T) currentItems[index];
  }

  @Override
  public Iterator<T> iterator() {
    return new Iterator<>() {
      private final Object[] items = CloseableCopyOnWriteList.this.items.get();
      private int index = 0;

      @Override
      public boolean hasNext() {
        return index < items.length;
      }

      @SuppressWarnings("unchecked")
      @Override
      public T next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return (T) items[index++];
      }
    };
  }

  @SuppressWarnings("unchecked")
  @Override
  public void forEach(Consumer<? super T> action) {
    for (var item : items.get()) {
      action.accept((T) item);
    }
  }

  @SuppressWarnings("unchecked")
  public List<T> close() {
    return List.of((T[]) items.getAndSet(closedSentinel));
  }
}
