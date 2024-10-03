/*
 * Copyright (c) 2024 Moataz Abdelnasser
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.util.Objects.requireNonNull;

import com.google.errorprone.annotations.InlineMe;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A generic object that holds a reference to the {@link Type} of its generic argument {@code T}.
 * This class utilizes the supertype-token idiom, which is used to capture complex types (e.g.
 * {@code List<String>}) that are otherwise impossible to represent using ordinary {@code Class}
 * objects.
 *
 * @param <T> represents the type this object holds a reference to
 */
public abstract class TypeRef<T> {
  private final Type type;
  private @MonotonicNonNull Class<?> lazyRawType;

  /**
   * Creates a new {@code TypeRef<T>} capturing the {@code Type} of {@code T}. This constructor is
   * typically invoked as an anonymous class expression (e.g. {@code new TypeRef<List<String>>()
   * {}}).
   *
   * @throws IllegalStateException if the raw version of this class is used
   */
  protected TypeRef() {
    var superclass = getClass().getGenericSuperclass();
    requireState(superclass instanceof ParameterizedType, "not used in parameterized form");
    this.type = ((ParameterizedType) superclass).getActualTypeArguments()[0];
  }

  private TypeRef(Type type) {
    this.type = type;
    lazyRawType = findRawType(type);
  }

  /** Returns the underlying {@link Type}. */
  public final Type type() {
    return type;
  }

  /**
   * Returns the {@code Class<? super T>} that represents the resolved raw type of {@code T}. The
   * returned class is {@code Class<? super T>} because {@code T} can possibly be a generic type,
   * and it is not semantically correct for a {@code Class} to be parameterized with such.
   *
   * @see #exactRawType()
   */
  @SuppressWarnings("unchecked")
  public final Class<? super T> rawType() {
    var rawType = lazyRawType;
    if (rawType == null) {
      try {
        rawType = findRawType(type);
      } catch (IllegalArgumentException e) {
        // lazyRawType is lazily initialized only if the type is not user-provided (obtained through
        // reflection), and in that case findRawType shouldn't fail.
        throw new AssertionError("Couldn't get raw type of <" + type + ">", e);
      }
      lazyRawType = rawType;
    }
    return (Class<? super T>) rawType;
  }

  /**
   * Returns the underlying type as a {@code Class<T>} for when it is known that {@link T} is
   * already raw. Similar to {@code (Class<T>) typeRef.type()}.
   *
   * @throws UnsupportedOperationException if the underlying type is not a raw type
   */
  @SuppressWarnings("unchecked")
  public final Class<T> exactRawType() {
    if (!(type instanceof Class<?>)) {
      throw new UnsupportedOperationException("<" + type + "> is not a raw type");
    }
    return (Class<T>) type;
  }

  /**
   * Performs an unchecked cast of the given object into {@code T}, at least ensuring that the given
   * value's raw type is assignable to the {@link #rawType() raw type} of {@code T}. This method
   * must be used with care when it comes to generics, where it is absolutely sure that {@code
   * value} is of that said generic type. For raw types, prefer {@code
   * typeRef.exactRawType().cast(value)}.
   */
  @SuppressWarnings("unchecked")
  public T uncheckedCast(Object value) {
    return (T) rawType().cast(value);
  }

  /**
   * Returns {@code true} if the given object is a {@code TypeRef} and both instances represent the
   * same type.
   */
  @Override
  public boolean equals(@Nullable Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof TypeRef)) {
      return false;
    }
    return type.equals(((TypeRef<?>) obj).type);
  }

  @Override
  public int hashCode() {
    return 31 * type.hashCode();
  }

  /** Returns a string representation for the type. */
  @Override
  public String toString() {
    return type.getTypeName();
  }

  private static Class<?> findRawType(Type type) {
    if (type instanceof Class) {
      return (Class<?>) type;
    } else if (type instanceof ParameterizedType) {
      var rawType = ((ParameterizedType) type).getRawType();
      requireArgument(
          rawType instanceof Class,
          "ParameterizedType::getRawType of %s returned a non-raw type: %s",
          type,
          rawType);
      return (Class<?>) rawType;
    } else if (type instanceof GenericArrayType) {
      // Here, the raw type is the type of the array created with the generic-component's raw type.
      var rawComponentType = findRawType(((GenericArrayType) type).getGenericComponentType());
      return Array.newInstance(rawComponentType, 0).getClass();
    } else if (type instanceof TypeVariable) {
      return rawUpperBound(((TypeVariable<?>) type).getBounds());
    } else if (type instanceof WildcardType) {
      return rawUpperBound(((WildcardType) type).getUpperBounds());
    } else {
      throw new IllegalArgumentException(
          "Unsupported specialization of java.lang.reflect.Type: <" + type + ">");
    }
  }

  private static Class<?> rawUpperBound(Type[] upperBounds) {
    // Same behaviour as Method::getGenericReturnType vs Method::getReturnType.
    return upperBounds.length > 0 ? findRawType(upperBounds[0]) : Object.class;
  }

  /**
   * Creates a new {@code TypeRef} for the given type.
   *
   * @deprecated in favor of the better-named {@link #of(Type)}.
   * @throws IllegalArgumentException if the given type is not a standard specialization of {@link
   *     Type}
   */
  @Deprecated
  @InlineMe(replacement = "TypeRef.of(type)", imports = "com.github.mizosoft.methanol.TypeRef")
  public static TypeRef<?> from(Type type) {
    return of(type);
  }

  /**
   * Creates a new {@code TypeRef} for the given type.
   *
   * @throws IllegalArgumentException if the given type is not a standard specialization of {@link
   *     Type}
   */
  public static TypeRef<?> of(Type type) {
    return new ExplicitTypeRef<>(type);
  }

  /**
   * Creates a new {@code TypeRef} from the given class.
   *
   * @deprecated in favor of the better-named {@link #of(Class)}
   */
  @Deprecated
  @InlineMe(replacement = "TypeRef.of(rawType)", imports = "com.github.mizosoft.methanol.TypeRef")
  public static <U> TypeRef<U> from(Class<U> rawType) {
    return of(rawType);
  }

  /** Creates a new {@code TypeRef} from the given class. */
  public static <U> TypeRef<U> of(Class<U> rawType) {
    return new ExplicitTypeRef<>(rawType);
  }

  /**
   * Returns a new {@code TypeRef} who's {@link #type()} is the runtime type of the given instance.
   */
  public static <T> TypeRef<? extends T> ofRuntimeType(T instance) {
    return new ExplicitTypeRef<>(instance.getClass());
  }

  private static final class ExplicitTypeRef<T> extends TypeRef<T> {
    ExplicitTypeRef(Type type) {
      super(requireNonNull(type));
    }
  }
}
