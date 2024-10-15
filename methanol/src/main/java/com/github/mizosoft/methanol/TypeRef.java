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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A generic object that holds a reference to the {@link Type} of its generic argument {@link T}.
 * This class utilizes the supertype-token idiom, which is used to capture complex types (e.g.
 * {@code List<String>}) that are otherwise impossible to represent using ordinary {@code Class}
 * objects.
 *
 * @param <T> represents the type this object holds a reference to
 */
public abstract class TypeRef<T> {
  private final Type type;
  private @MonotonicNonNull Class<?> lazyRawType;
  private @MonotonicNonNull List<TypeRef<?>> lazyTypeArguments;

  /**
   * Creates a new {@code TypeRef<T>} capturing the {@link Type} of {@link T}. This constructor is
   * typically invoked as an anonymous class expression (e.g. {@code new TypeRef<List<String>>()
   * {}}).
   *
   * @throws IllegalStateException if the raw version of this class is used
   */
  protected TypeRef() {
    var superclass = getClass().getGenericSuperclass();
    requireState(
        superclass instanceof ParameterizedType,
        "TypeRef must be used in parameterized form (i.e. new TypeRef<List<String>>() {})");
    this.type = ((ParameterizedType) superclass).getActualTypeArguments()[0];
  }

  private TypeRef(Type type) {
    this.type = type;
    lazyRawType = rawTypeOf(type);
  }

  /** Returns the underlying {@link Type}. */
  public final Type type() {
    return type;
  }

  /**
   * Returns the {@code Class<? super T>} that represents the resolved raw type of {@link T}. The
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
        rawType = rawTypeOf(type);
      } catch (IllegalArgumentException e) {
        // lazyRawType is lazily initialized only if the type is not user-provided (obtained through
        // reflection), and in that case findRawType shouldn't fail.
        throw new AssertionError("Couldn't get raw type of <" + type + ">", e);
      }
      lazyRawType = rawType;
    }
    return (Class<? super T>) rawType;
  }

  /** Returns {@code true} if {@link T} is a raw type (i.e. {@code Class<T>}). */
  public final boolean isRawType() {
    return type instanceof Class<?>;
  }

  /** Returns {@code true} if {@link T} is a {@link ParameterizedType parameterized type}. */
  public final boolean isParameterizedType() {
    return type instanceof ParameterizedType;
  }

  /** Returns {@code true} if {@link T} is a {@link GenericArrayType generic array}. */
  public final boolean isGenericArray() {
    return type instanceof GenericArrayType;
  }

  /** Returns {@code true} if {@link T} is a {@link TypeVariable type variable}. */
  public final boolean isTypeVariable() {
    return type instanceof TypeVariable<?>;
  }

  /** Returns {@code true} if {@link T} is a {@link WildcardType wildcard}. */
  public final boolean isWildcard() {
    return type instanceof WildcardType;
  }

  /**
   * Returns the type argument of {@link T} at the given index, provided that {@code T} is a {@link
   * ParameterizedType parameterized type} that has at least as many arguments as the given index.
   *
   * <p>For instance:
   *
   * <ul>
   *   <li>{@code new TypeRef<List<String>>() {}.typeArgumentAt(0) => String}
   *   <li>{@code new TypeRef<List>() {}.typeArgumentAt(0) => No result}
   *   <li>{@code TypeRef.of(StringList.class).resolveSupertype(List.class).typeArgumentAt(0) =>
   *       String} where {@code StringList implements List<String>}
   * </ul>
   */
  public final Optional<TypeRef<?>> typeArgumentAt(int i) {
    requireArgument(i >= 0, "Negative index: %d", i);
    var typeArguments = typeArguments();
    return i < typeArguments.size() ? Optional.of(typeArguments.get(i)) : Optional.empty();
  }

  /**
   * Returns the underlying type as a {@code Class<T>} for when it is known that {@link T} is a raw
   * type. Equivalent to {@code (Class<T>) type.type()}.
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
   * Performs an unchecked cast of the given object into {@link T}, at least ensuring that the given
   * value's raw type is assignable to the {@link #rawType() raw type} of {@code T}. This method
   * must be used with care when it comes to generics, only when one is sure that {@code value} is
   * of the generic type represented by this {@code TypeRef}. For raw types, prefer {@code
   * typeRef.exactRawType().cast(value)}.
   */
  @SuppressWarnings("unchecked")
  public final T uncheckedCast(Object value) {
    return (T) rawType().cast(value);
  }

  /**
   * Resolves the given supertype into a type with concrete type arguments (if any) that are derived
   * from this type (i.e. {@link T}).
   *
   * <p>For instance:
   *
   * <ul>
   *   <li>{@code new TypeRef<ArrayList<String>>() {}.resolveSupertype(List.class) => List<String>}
   *   <li>{@code TypeRef.of(StringList.class).resolveSupertype(List.class) => List<String>} where
   *       {@code StringList implements List<String>}
   *   <li>{@code new TypeRef<ListOfArrayOf<String>>() {}.resolveSupertype(List.class) =>
   *       List<String[]>} where {@code ListOfArrayOf<T> implements List<T[]>}
   * </ul>
   *
   * @throws IllegalArgumentException if the given type is not a supertype of the type represented
   *     by this {@code TypeRef}
   */
  @SuppressWarnings("unchecked")
  public final TypeRef<? super T> resolveSupertype(Class<?> supertype) {
    var resolved = resolve(type, supertype);
    requireArgument(resolved != null, "<%s> is not a supertype of <%>", supertype, type);
    return (TypeRef<? super T>) TypeRef.of(resolved);
  }

  /**
   * Returns a list of {@code TypeRef<?>} corresponding to {@link T}'s type arguments, provided it
   * is a {@link ParameterizedType parameterized type}, otherwise an empty list is returned.
   */
  public final List<TypeRef<?>> typeArguments() {
    var typeArguments = lazyTypeArguments;
    if (typeArguments == null) {
      typeArguments = computeTypeArguments();
      lazyTypeArguments = typeArguments;
    }
    return typeArguments;
  }

  private List<TypeRef<?>> computeTypeArguments() {
    if (!(type instanceof ParameterizedType)) {
      return List.of();
    }

    var result = new ArrayList<TypeRef<?>>();
    for (var type : ((ParameterizedType) type).getActualTypeArguments()) {
      result.add(TypeRef.of(type));
    }
    return Collections.unmodifiableList(result);
  }

  /**
   * Returns {@code true} if the given object is a {@code TypeRef} and both instances represent the
   * same type.
   */
  @Override
  public final boolean equals(@Nullable Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof TypeRef)) {
      return false;
    }
    return type.equals(((TypeRef<?>) obj).type);
  }

  @Override
  public final int hashCode() {
    return 31 * type.hashCode();
  }

  /** Returns a string representation for the type. */
  @Override
  public final String toString() {
    return type.getTypeName();
  }

  /**
   * Returns a new {@code TypeRef} who's {@link #type()} is the given type.
   *
   * @deprecated in favor of {@link #of(Type)}.
   * @throws IllegalArgumentException if the given type is not a standard specialization of {@link
   *     Type}
   */
  @Deprecated
  @InlineMe(replacement = "TypeRef.of(type)", imports = "com.github.mizosoft.methanol.TypeRef")
  public static TypeRef<?> from(Type type) {
    return of(type);
  }

  /**
   * Returns a new {@code TypeRef} who's {@link #type()} is the given type.
   *
   * @throws IllegalArgumentException if the given type instance is not a standard specialization of
   *     {@link Type}
   */
  public static TypeRef<?> of(Type type) {
    return new ExplicitTypeRef<>(type);
  }

  /**
   * Returns a new {@code TypeRef} who's {@link #type()} is the given class.
   *
   * @deprecated in favor of {@link #of(Class)}
   */
  @Deprecated
  @InlineMe(replacement = "TypeRef.of(rawType)", imports = "com.github.mizosoft.methanol.TypeRef")
  public static <U> TypeRef<U> from(Class<U> rawType) {
    return of(rawType);
  }

  /** Returns a new {@code TypeRef} who's {@link #type()} is the given class. */
  public static <U> TypeRef<U> of(Class<U> rawType) {
    return new ExplicitTypeRef<>(rawType);
  }

  /**
   * Returns a new {@code TypeRef} who's {@link #type()} is the runtime type of the given instance.
   */
  public static <T> TypeRef<? extends T> ofRuntimeType(T instance) {
    return new ExplicitTypeRef<>(instance.getClass());
  }

  private static Class<?> rawTypeOf(Type type) {
    if (type instanceof Class<?>) {
      return (Class<?>) type;
    } else if (type instanceof ParameterizedType) {
      var rawType = ((ParameterizedType) type).getRawType();
      requireArgument(
          rawType instanceof Class,
          "ParameterizedType::getRawType of <%s> returned a non-raw type <%s>",
          type,
          rawType);
      return (Class<?>) rawType;
    } else if (type instanceof GenericArrayType) {
      // Here, the raw type is the type of the array created with the generic-component's raw type.
      var rawComponentType = rawTypeOf(((GenericArrayType) type).getGenericComponentType());
      return Array.newInstance(rawComponentType, 0).getClass();
    } else if (type instanceof TypeVariable<?>) {
      return rawUpperBound(((TypeVariable<?>) type).getBounds());
    } else if (type instanceof WildcardType) {
      return rawUpperBound(((WildcardType) type).getUpperBounds());
    } else {
      throw new IllegalArgumentException(
          "Unknown specialization of java.lang.reflect.Type: <" + type + ">");
    }
  }

  private static Class<?> rawUpperBound(Type[] upperBounds) {
    // Same behaviour as Method::getGenericReturnType vs Method::getReturnType.
    return upperBounds.length > 0 ? rawTypeOf(upperBounds[0]) : Object.class;
  }

  private static @Nullable Type resolve(Type spec, Class<?> supertype) {
    if (spec instanceof Class<?>) {
      var rawSpec = (Class<?>) spec;
      if (rawSpec.isArray()) {
        var supertypeComponent = supertype.getComponentType();
        requireArgument(
            supertypeComponent != null,
            "Type specialization <%s> is an array but supertype <%s> isn't",
            spec,
            supertype);
        return arrayTypeOf(resolve(rawSpec.getComponentType(), supertypeComponent));
      } else {
        return resolve(rawSpec, supertype);
      }
    } else if (spec instanceof ParameterizedType) {
      return resolve((ParameterizedType) spec, supertype);
    } else if (spec instanceof GenericArrayType) {
      var supertypeComponent = supertype.getComponentType();
      requireArgument(
          supertypeComponent != null,
          "Type specialization <%s> is an array but supertype <%s> isn't",
          spec,
          supertype);
      return arrayTypeOf(
          resolve(((GenericArrayType) spec).getGenericComponentType(), supertypeComponent));
    } else if (spec instanceof TypeVariable<?>) {
      return resolveFromAny(((TypeVariable<?>) spec).getBounds(), supertype);
    } else if (spec instanceof WildcardType) {
      return resolveFromAny(((WildcardType) spec).getUpperBounds(), supertype);
    } else {
      throw new IllegalArgumentException(
          "Unknown specialization of java.lang.reflect.Type: <" + spec + ">");
    }
  }

  private static @Nullable Type resolve(Class<?> spec, Class<?> supertype) {
    if (spec == supertype) {
      return spec;
    }
    if (!supertype.isAssignableFrom(spec)) {
      return null;
    }
    return resolveFromSupertypes(spec, supertype);
  }

  private static @Nullable Type resolve(ParameterizedType spec, Class<?> supertype) {
    var rawSpec = rawTypeOf(spec);
    if (rawSpec == supertype) {
      return spec;
    }
    if (!supertype.isAssignableFrom(rawSpec)) {
      return null;
    }
    var resolved = resolveFromSupertypes(rawSpec, supertype);
    return resolved != null ? substitute(spec, resolved) : null;
  }

  private static @Nullable Type resolveFromSupertypes(Class<?> rawSpec, Class<?> supertype) {
    var resolved = resolveFromAny(rawSpec.getGenericInterfaces(), supertype);
    return resolved != null ? resolved : resolve(rawSpec.getGenericSuperclass(), supertype);
  }

  private static @Nullable Type resolveFromAny(Type[] upperBounds, Class<?> supertype) {
    for (var bound : upperBounds) {
      var resolved = resolve(bound, supertype);
      if (resolved != null) {
        return resolved;
      }
    }
    return null;
  }

  private static Type substitute(ParameterizedType spec, Type target) {
    requireNonNull(target);
    if (target instanceof Class<?>) {
      return target;
    } else if (target instanceof ParameterizedType) {
      var parameterizedTarget = (ParameterizedType) target;
      var arguments = parameterizedTarget.getActualTypeArguments();
      var substitutedArguments = substituteAll(spec, arguments);
      var owner = parameterizedTarget.getOwnerType();
      var substitutedOwner = owner != null ? substitute(spec, owner) : null;
      return substitutedArguments != arguments || substitutedOwner != owner
          ? new ParameterizedTypeImpl(
              substitutedArguments, substitutedOwner, parameterizedTarget.getRawType())
          : target;
    } else if (target instanceof GenericArrayType) {
      var arrayTarget = (GenericArrayType) target;
      var componentType = arrayTarget.getGenericComponentType();
      var substitutedComponentType = substitute(spec, componentType);
      return componentType != substitutedComponentType
          ? arrayTypeOf(substitutedComponentType)
          : target;
    } else if (target instanceof TypeVariable<?>) {
      var typeVariableTarget = (TypeVariable<?>) target;
      var currentSpec = spec;
      while (true) {
        var rawCurrentSpec = rawTypeOf(currentSpec);
        int j;
        if (typeVariableTarget.getGenericDeclaration() == rawCurrentSpec
            && (j = indexOf(rawCurrentSpec.getTypeParameters(), typeVariableTarget)) >= 0) {
          return currentSpec.getActualTypeArguments()[j];
        }

        var currentSpecOwner = currentSpec.getOwnerType();
        if (!(currentSpecOwner instanceof ParameterizedType)) {
          break;
        }
        currentSpec = (ParameterizedType) currentSpecOwner;
      }

      // If we cannot substitute a variable, we may think we should substitute its bounds in case
      // they lead to a type variable which can be substituted. In addition to this adding
      // considerable complications (e.g., our TypeVariableImpl won't compare equal to JDK's, what
      // will we do with AnnotatedType bounds?, ...), specifying a type variable A without
      // specifying B which reaches A through its bounds doesn't seem realizable unless there's some
      // trickery from caller. For instance, the following doesn't compile:
      //   abstract class I<A, B extends List<A>> {
      //     I<String, B> f() { // error: Type parameter B is not within its bound...
      //       return null;
      //     }
      //   }
      return target;
    } else if (target instanceof WildcardType) {
      var wildcardTarget = (WildcardType) target;
      var upperBounds = wildcardTarget.getUpperBounds();
      var substitutedUpperBounds = substituteAll(spec, upperBounds);
      var lowerBounds = wildcardTarget.getLowerBounds();
      var substitutedLowerBounds = substituteAll(spec, lowerBounds);
      return substitutedUpperBounds != upperBounds || substitutedLowerBounds != lowerBounds
          ? new WildcardTypeImpl(substitutedUpperBounds, substitutedLowerBounds)
          : target;
    } else {
      throw new IllegalArgumentException(
          "Unknown specialization of java.lang.reflect.Type: <" + target + ">");
    }
  }

  private static Type[] substituteAll(ParameterizedType spec, Type[] types) {
    Type[] substitutedTypes = null;
    for (int i = 0; i < types.length; i++) {
      var type = types[i];
      var substitutedType = substitute(spec, type);
      if (substitutedType != type) {
        if (substitutedTypes == null) {
          // Avoid ArrayStoreExceptions by ensuring the most generic type.
          substitutedTypes = Arrays.copyOf(types, types.length, Type[].class);
        }
        substitutedTypes[i] = substitutedType;
      }
    }
    return substitutedTypes != null ? substitutedTypes : types;
  }

  private static Type arrayTypeOf(Type componentType) {
    return componentType instanceof Class<?>
        ? Array.newInstance((Class<?>) componentType, 0).getClass()
        : new GenericArrayTypeImpl(componentType);
  }

  private static <T> int indexOf(T[] array, T element) {
    for (int i = 0; i < array.length; i++) {
      if (array[i].equals(element)) {
        return i;
      }
    }
    return -1;
  }

  private static Type[] nonNullCopy(Type[] types) {
    var copy = Arrays.copyOf(types, types.length);
    for (var type : copy) {
      requireNonNull(type);
    }
    return copy;
  }

  private static final class ExplicitTypeRef<T> extends TypeRef<T> {
    ExplicitTypeRef(Type type) {
      super(requireNonNull(type));
    }
  }

  private static final class GenericArrayTypeImpl implements GenericArrayType {
    private final Type componentType;

    GenericArrayTypeImpl(Type componentType) {
      this.componentType = requireNonNull(componentType);
    }

    @Override
    public Type getGenericComponentType() {
      return componentType;
    }

    @Override
    public int hashCode() {
      return componentType.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      }
      if (!(obj instanceof GenericArrayType)) {
        return false;
      }
      return componentType.equals(((GenericArrayType) obj).getGenericComponentType());
    }

    public String toString() {
      return componentType.getTypeName() + "[]";
    }
  }

  private static final class ParameterizedTypeImpl implements ParameterizedType {
    private final Type[] typeArguments;
    private final @Nullable Type ownerType;
    private final Class<?> rawType;

    ParameterizedTypeImpl(Type[] typeArguments, @Nullable Type ownerType, Type rawType) {
      this.typeArguments = nonNullCopy(typeArguments);
      this.ownerType = ownerType;
      requireArgument(
          requireNonNull(rawType) instanceof Class<?>, "<%s> is not a raw type", rawType);
      this.rawType = (Class<?>) rawType;
    }

    @Override
    public Type[] getActualTypeArguments() {
      return typeArguments.clone();
    }

    @Override
    public Type getRawType() {
      return rawType;
    }

    @Override
    public @Nullable Type getOwnerType() {
      return ownerType;
    }

    @Override
    public String toString() {
      var sb = new StringBuilder();
      if (ownerType != null) {
        sb.append(ownerType.getTypeName()).append("$").append(rawType.getSimpleName());
      } else {
        sb.append(rawType.getName());
      }
      if (typeArguments.length > 0) {
        var joiner = new StringJoiner(", ", "<", ">").setEmptyValue("");
        for (var type : typeArguments) {
          joiner.add(type.getTypeName());
        }
        sb.append(joiner);
      }
      return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      }
      if (!(obj instanceof ParameterizedType)) {
        return false;
      }
      var other = (ParameterizedType) obj;
      return Arrays.equals(typeArguments, other.getActualTypeArguments())
          && Objects.equals(ownerType, other.getOwnerType())
          && rawType.equals(other.getRawType());
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(typeArguments) ^ Objects.hash(ownerType) ^ rawType.hashCode();
    }
  }

  private static final class WildcardTypeImpl implements WildcardType {
    private final Type[] upperBounds;
    private final Type[] lowerBounds;

    WildcardTypeImpl(Type[] upperBounds, Type[] lowerBounds) {
      this.upperBounds = nonNullCopy(upperBounds);
      this.lowerBounds = nonNullCopy(lowerBounds);
      requireArgument(
          upperBounds.length > 0 && (upperBounds[0] == Object.class || lowerBounds.length == 0),
          "Inconsistent bounds for a WildcardType");
    }

    @Override
    public Type[] getUpperBounds() {
      return upperBounds.clone();
    }

    @Override
    public Type[] getLowerBounds() {
      return lowerBounds.clone();
    }

    @Override
    public String toString() {
      String prefix;
      Type[] boundsToStringify;
      if (lowerBounds.length > 0) {
        prefix = "? super ";
        boundsToStringify = lowerBounds;
      } else if (upperBounds[0].equals(Object.class)) {
        return "?";
      } else {
        prefix = "? extends ";
        boundsToStringify = upperBounds;
      }

      var joiner = new StringJoiner(" & ");
      for (var bound : boundsToStringify) {
        joiner.add(bound.getTypeName());
      }
      return prefix + joiner;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      }
      if (!(obj instanceof WildcardType)) {
        return false;
      }
      var other = (WildcardType) obj;
      return Arrays.equals(upperBounds, other.getUpperBounds())
          && Arrays.equals(lowerBounds, other.getLowerBounds());
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(upperBounds) ^ Arrays.hashCode(lowerBounds);
    }
  }
}
