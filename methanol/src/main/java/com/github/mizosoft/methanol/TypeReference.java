/*
 * MIT License
 *
 * Copyright (c) 2019 Moataz Abdelnasser
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

/**
 * An object that represents the {@link Type} of the generic argument {@code T}. This class utilizes
 * the supertype-token idiom, which is used to capture complex types (i.e. generic collections) that
 * are otherwise impossible to represent using ordinary {@code Class} objects.
 *
 * @param <T> the type this object represents
 */
public abstract class TypeReference<T> {

  private final Type type;
  private @MonotonicNonNull Class<? super T> rawType;

  /**
   * Creates a new {@code TypeReference<T>} capturing the {@code Type} of {@code T}. It is usually
   * the case that this constructor is invoked as an anonymous class expression (e.g. {@code new
   * TypeReference<List<String>>() {}}).
   *
   * @throws IllegalStateException if the raw version of this class is used
   */
  protected TypeReference() {
    Type superClass = getClass().getGenericSuperclass();
    requireState(superClass instanceof ParameterizedType, "not used in parameterized form");
    this.type = ((ParameterizedType) superClass).getActualTypeArguments()[0];
  }

  @SuppressWarnings("unchecked")
  private TypeReference(Type type) {
    this.type = type;
    rawType = (Class<? super T>) findRawType(type);
  }

  /**
   * Returns the underlying java {@link Type}.
   */
  public final Type type() {
    return type;
  }

  /**
   * Returns the {@code Class} object that represents the raw type of {@code T}.
   */
  @SuppressWarnings("unchecked")
  public final Class<? super T> rawType() {
    Class<? super T> clz = rawType;
    if (clz == null) {
      try {
        clz = (Class<? super T>) findRawType(type);
      } catch (IllegalArgumentException e) {
        // rawType is lazily initialized only if not user-provided and
        // on that case findRawType shouldn't fail
        throw new AssertionError("couldn't get raw type of: " + type, e);
      }
      rawType = clz;
    }
    return clz;
  }

  /**
   * Returns {@code true} if the given object is a {@code TypeReference} and both instances
   * represent the same type.
   *
   * @param obj the object to test for equality
   */
  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof TypeReference)) {
      return false;
    }
    return type.equals(((TypeReference<?>) obj).type);
  }

  @Override
  public int hashCode() {
    return 31 * type.hashCode();
  }

  /**
   * Returns a string representation for the type.
   */
  @Override
  public String toString() {
    return type.getTypeName();
  }

  private static Class<?> findRawType(Type type) {
    if (type instanceof Class) {
      return (Class<?>) type;
    }
    if (type instanceof ParameterizedType) {
      Type rawType = ((ParameterizedType) type).getRawType();
      requireArgument(rawType instanceof Class,
          "ParameterizedType::getRawType of %s returned a non-raw type: %s", type, rawType);
      return (Class<?>) rawType;
    }
    if (type instanceof GenericArrayType) {
      // Here, the raw type is the type of the array created with the generic-component's raw type
      Class<?> rawComponentType = findRawType(((GenericArrayType) type).getGenericComponentType());
      return Array.newInstance(rawComponentType, 0).getClass();
    }
    if (type instanceof TypeVariable) {
      return rawUpperBound(((TypeVariable<?>) type).getBounds());
    }
    if (type instanceof WildcardType) {
      return rawUpperBound(((WildcardType) type).getUpperBounds());
    }
    throw new IllegalArgumentException("unsupported specialization of Type: " + type);
  }

  private static Class<?> rawUpperBound(Type[] upperBounds) {
    // Same behaviour as Method::getGenericReturnType vs Method::getReturnType
    return upperBounds.length > 0 ? findRawType(upperBounds[0]) : Object.class;
  }

  /**
   * Creates a new {@code TypeReference} from the given type.
   *
   * @param type the type
   * @throws IllegalArgumentException if the given type is not a standard specialization of a java
   *                                  {@code Type}
   */
  public static TypeReference<?> from(Type type) {
    return new ExplicitTypeReference<>(type);
  }

  /**
   * Creates a new {@code TypeReference} from the given class.
   *
   * @param rawType the class
   * @param <U>     the raw type that the given class represents
   */
  public static <U> TypeReference<U> from(Class<U> rawType) {
    return new ExplicitTypeReference<>(rawType);
  }

  private static final class ExplicitTypeReference<T> extends TypeReference<T> {

    ExplicitTypeReference(Type type) {
      super(requireNonNull(type));
    }
  }
}
