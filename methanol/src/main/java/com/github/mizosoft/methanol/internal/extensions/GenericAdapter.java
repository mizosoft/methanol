package com.github.mizosoft.methanol.internal.extensions;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.*;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

public abstract class GenericAdapter extends AbstractBodyAdapter  {
  GenericAdapter() {
    super(MediaType.ANY);
  }

  static final class GenericEncoder extends GenericAdapter implements Encoder {
    private static final Map<Class<?>, Function<?, ? extends BodyPublisher>> ENCODERS;

    static {
      var encoders = new LinkedHashMap<Class<?>, Function<?, ? extends BodyPublisher>>();
      addEncoder(encoders, String.class, GenericEncoder::encodeString);
      addEncoder(encoders, InputStream.class, GenericEncoder::encodeInputStream);
      addEncoder(encoders, byte[].class, GenericEncoder::encodeByteArray);
      addEncoder(encoders, ByteBuffer.class, GenericEncoder::encodeByteBuffer);
      addEncoder(encoders, Path.class, GenericEncoder::encodePath);
      addEncoder(encoders, Supplier.class, GenericEncoder::encodeSupplier);
      ENCODERS = Collections.unmodifiableMap(encoders);
    }

    private static <T> void addEncoder(
        Map<Class<?>, 
            Function<?, ? extends BodyPublisher>> encoders, Class<T> type, Function<T, ? extends BodyPublisher> encoder) {
      encoders.put(type, encoder);
    }

    GenericEncoder() {}

    @Override
    public boolean supportsType(TypeRef<?> typeRef) {
      for (var rawType : ENCODERS.keySet()) {
        if (isRawTypeAssignableFrom(rawType, typeRef.type())) {
          return true;
        }
      }
      return false;
    }
    
    

    private static BodyPublisher encodeString(String value) {
      return BodyPublishers.ofString(value);
    }

    private static BodyPublisher encodeInputStream(InputStream in) {
      return BodyPublishers.ofInputStream(() -> in);
    }

    private static BodyPublisher encodeByteArray(byte[] bytes) {
      return BodyPublishers.ofByteArray(bytes);
    }

    private static BodyPublisher encodePath(Path path) {
      try {
        return BodyPublishers.ofFile(path);
      } catch (FileNotFoundException e) {
        throw new UncheckedIOException(e);
      }
    }

    private static BodyPublisher encodeByteBuffer(ByteBuffer buffer) {
      if (buffer.hasArray()) {
        return BodyPublishers.ofByteArray(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
      } else {
        return BodyPublishers.fromPublisher(new ScalarPublisher(buffer), buffer.remaining());
      }
    }

    private static BodyPublisher encodeSupplier(Supplier<?> supplier) {
      return BodyPublishers.fromPublisher(new SupplierPublisher(supplier));
    }

    @SuppressWarnings("unchecked")
    private static <T> Function<? super T, BodyPublisher> encoderOf(Class<T> type) {
      for (var entry : ENCODERS.entrySet()) {
        if (isRawTypeAssignableFrom(entry.getKey(), type)) {
          return (Function<? super T, BodyPublisher>) entry.getValue();
        }
      }
      throw new UnsupportedOperationException("Unsupported conversion from an object of type <" + type + ">");
    }

    @SuppressWarnings("unchecked")
    private static BodyPublisher encodeObject(Object value) {
      return GenericEncoder.encoderOf((Class<Object>) value.getClass()).apply(value);
    }

    private static boolean isRawTypeAssignableFrom(Class<?> left, Type right) {
      if (right instanceof Class<?> || right instanceof ParameterizedType) {
        return left.isAssignableFrom(TypeRef.from(right).rawType());
      } else if (right instanceof GenericArrayType) {
        // Arrays are covariant, so T[] is assignable from R[] if T is assignable from R.
        var leftComponentType = left.getComponentType();
        return leftComponentType != null && isRawTypeAssignableFrom(leftComponentType, ((GenericArrayType) right).getGenericComponentType());
      } else if (right instanceof TypeVariable<?>) {
        return isRawTypeAssignableFromAny(left, ((TypeVariable<?>) right).getBounds());
      } else if (right instanceof WildcardType) {
        return isRawTypeAssignableFromAny(left,  ((WildcardType) right).getUpperBounds());
      } else {
        throw new IllegalArgumentException("what?");
      }
    }

    private static boolean isRawTypeAssignableFromAny(Class<?> left, Type[] rights) {
      for (var right : rights) {
        if (isRawTypeAssignableFrom(left, right)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public BodyPublisher toBody(Object object, @Nullable MediaType mediaType) {
      requireSupport(object.getClass());
      requireCompatibleOrNull(mediaType);
      return encodeObject(object);
    }

    private static final class SupplierPublisher implements Publisher<ByteBuffer> {
      private final Supplier<?> supplier;

      SupplierPublisher(Supplier<?> supplier) {
        this.supplier = requireNonNull(supplier);
      }

      @Override
      public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
        requireNonNull(subscriber);
        
        BodyPublisher publisher;
        try {
          publisher = encodeObject(supplier.get());
        } catch (Throwable e) {
          try {
            subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
          } catch (Throwable onSubscribeEx) {
            e.addSuppressed(onSubscribeEx);
          } finally {
            subscriber.onError(e);
          }
          return;
        }
        
        publisher.subscribe(subscriber);
      }
    }

    private static final class ScalarPublisher implements Publisher<ByteBuffer> {
      private final ByteBuffer buffer;

      private ScalarPublisher(ByteBuffer buffer) {
        this.buffer = buffer;
      }

      @Override
      public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
        requireNonNull(subscriber);
        new ScalarSubscription(subscriber, buffer).onSubscribe();
      }

      private static final class ScalarSubscription implements Subscription {
        private final AtomicBoolean done = new AtomicBoolean();
        
        private final Subscriber<? super ByteBuffer> downstream;
        private final ByteBuffer buffer; 

        ScalarSubscription(Subscriber<? super ByteBuffer> downstream, ByteBuffer buffer) {
          this.downstream = downstream;
          this.buffer = buffer;
        }

        @Override
        public void request(long n) {
          if (done.compareAndSet(false, true)) {
            if (n <= 0) {
              downstream.onError(FlowSupport.illegalRequest());
            } else {
              try {
                downstream.onNext(buffer);
              } catch (Throwable e) {
                downstream.onError(e);
              }
              downstream.onComplete();
            }
          }
        }

        @Override
        public void cancel() {
          done.set(true);
        }

        void onSubscribe() {
          try {
            downstream.onSubscribe(this);
          } catch (Throwable e) {
            if (done.compareAndSet(false, true)) {
              downstream.onError(e);
            }
          }
        }
      }
    }
  }
  
  private static final class GenericDecoder extends GenericAdapter implements Decoder {
    GenericDecoder() {}

    @Override
    public boolean supportsType(TypeRef<?> type) {
      return false;
    }

    @Override
    public <T> HttpResponse.BodySubscriber<T> toObject(TypeRef<T> objectType, @Nullable MediaType mediaType) {
      return null;
    }
  }
}
