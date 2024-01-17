/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
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

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An object that represents the {@link Type} of the generic argument {@code T}. This class utilizes
 * the supertype-token idiom, which is used to capture complex types (i.e. generic types) that are
 * otherwise impossible to represent using ordinary {@code Class} objects.
 *
 * @param <T> the type this object represents
 */
public abstract class TypeRef<T> {
  private final Type genericType;
  private @MonotonicNonNull Class<?> lazyRawType;

  /**
   * Creates a new {@code TypeRef<T>} capturing the {@link Type} of {@code T}. This constructor is
   * meant to be invoked in an anonymous class expression (e.g. {@code new TypeRef<List<String>>()
   * {}}).
   *
   * @throws IllegalStateException if the raw version of this class is used
   */
  protected TypeRef() {
    var superClass = getClass().getGenericSuperclass();
    requireState(superClass instanceof ParameterizedType, "not used in parameterized form");
    this.genericType = ((ParameterizedType) superClass).getActualTypeArguments()[0];
  }

  private TypeRef(Type genericType) {
    this.genericType = genericType;
    this.lazyRawType = findRawType(genericType);
  }

  /**
   * Returns the underlying generic {@link Type}.
   *
   * @deprecated In favor of {@link #genericType()}.
   */
  public final Type type() {
    return genericType;
  }

  /** Returns the underlying generic {@link Type}. */
  public final Type genericType() {
    return genericType;
  }

  /**
   * Returns the raw type of {@code T}. The returned class is {@code Class<? super T>} because
   * {@code T} can possibly be a generic type, and it is not semantically correct for a {@code
   * Class} to be parameterized with such.
   *
   * @see #exactRawType()
   */
  @SuppressWarnings("unchecked")
  public final Class<? super T> rawType() {
    var rawType = lazyRawType;
    if (rawType == null) {
      try {
        rawType = findRawType(genericType);
      } catch (IllegalArgumentException e) {
        // lazyRawType is lazily initialized only if not user-provided and on that case findRawType
        // shouldn't fail.
        throw new AssertionError("Couldn't get raw type of: " + genericType, e);
      }
      lazyRawType = rawType;
    }
    return (Class<? super T>) rawType;
  }

  /**
   * Returns the underlying type as a {@code Class<T>} for when it is known that {@link T} is a raw
   * type. Similar to {@code (Class<T>) typeRef.type()}.
   *
   * @throws UnsupportedOperationException if the underlying type is not a raw type
   */
  @SuppressWarnings("unchecked")
  public final Class<T> exactRawType() {
    if (!(genericType instanceof Class<?>)) {
      throw new UnsupportedOperationException("<" + genericType + "> is not a raw type");
    }
    return (Class<T>) genericType;
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
    return genericType.equals(((TypeRef<?>) obj).genericType);
  }

  @Override
  public int hashCode() {
    return 31 * genericType.hashCode();
  }

  @Override
  public String toString() {
    return genericType.getTypeName();
  }

  private static Class<?> findRawType(Type type) {
    if (type instanceof Class<?>) {
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
    }
    throw new IllegalArgumentException(
        "unsupported specialization of java.lang.reflect.Type: " + type);
  }

  private static Class<?> rawUpperBound(Type[] upperBounds) {
    // Same behaviour as Method::getGenericReturnType vs Method::getReturnType.
    return upperBounds.length > 0 ? findRawType(upperBounds[0]) : Object.class;
  }

  /**
   * Creates a new {@code TypeRef} for the given generic type.
   *
   * @throws IllegalArgumentException if the given type is not a standard specialization of a Java
   *     {@code Type}
   */
  public static TypeRef<?> from(Type genericType) {
    return new ExplicitTypeRef<>(genericType);
  }

  /**
   * Creates a new {@code TypeRef} for the given raw type.
   *
   * @throws IllegalArgumentException if the given type is not a standard specialization of a Java
   *     {@code Type}
   */
  public static <U> TypeRef<U> from(Class<U> rawType) {
    return new ExplicitTypeRef<>(rawType);
  }

  public static TypeRef<?> of(Type genericType) {
    return new ExplicitTypeRef<>(genericType);
  }

  public static <U> TypeRef<U> of(Class<U> rawType) {
    return new ExplicitTypeRef<>(rawType);
  }

  private static final class ExplicitTypeRef<T> extends TypeRef<T> {
    ExplicitTypeRef(Type genericType) {
      super(requireNonNull(genericType));
    }
  }
}
