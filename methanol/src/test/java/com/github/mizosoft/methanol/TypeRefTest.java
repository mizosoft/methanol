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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class TypeRefTest {

  @Test
  void new_rawType() {
    var ref = new TypeRef<String>() {};
    assertEquals(String.class, ref.type());
  }

  @Test
  void new_parameterizedType() {
    var ref = new TypeRef<List<String>>() {};
    assertEquals(StringList.type, ref.type());
  }

  // raw type resolution tests

  @Test
  void rawType_rawType() {
    var ref = new TypeRef<String>() {};
    assertEquals(String.class, ref.rawType());
  }

  @Test
  void rawType_parameterizedType() {
    var ref = new TypeRef<List<String>>() {};
    assertEquals(List.class, ref.rawType());
  }

  @Test
  <T> void rawType_typeVariableNoBounds() {
    var ref = new TypeRef<T>() {};
    assertEquals(Object.class, ref.rawType());
  }

  @Test
  <T extends Dummy1> void rawType_typeVariableOneBound() {
    var ref = new TypeRef<T>() {};
    assertEquals(Dummy1.class, ref.rawType());
  }

  @Test
  <T extends Dummy1 & Dummy2> void rawType_typeVariableTwoBounds() {
    var ref = new TypeRef<T>() {};
    assertEquals(Dummy1.class, ref.rawType()); // First bound is considered as the raw type
  }

  @Test
  void rawType_wildcardNoBounds() {
    var wildCard = ((ParameterizedType) new TypeRef<List<?>>() {}.type())
        .getActualTypeArguments()[0];
    var ref = TypeRef.from(wildCard);
    assertTrue(ref.type() instanceof WildcardType);
    assertEquals(Object.class, ref.rawType());
  }

  @Test
  void rawType_wildcardOneBounds() {
    var wildCard = ((ParameterizedType) new TypeRef<List<? extends Dummy1>>() {}.type())
        .getActualTypeArguments()[0];
    var ref = TypeRef.from(wildCard);
    assertTrue(ref.type() instanceof WildcardType);
    assertEquals(Dummy1.class, ref.rawType());
  }

  @Test
  void rawType_parameterizedTypeArray() {
    var ref = new TypeRef<List<String>[]>() {};
    assertEquals(List[].class, ref.rawType());
  }

  @Test
  <T> void rawType_typeVariableArrayNoBounds() {
    var ref = new TypeRef<T[]>() {};
    assertEquals(Object[].class, ref.rawType());
  }

  @Test
  <T extends Dummy1 & Dummy2> void rawType_typeVariableArrayTwoBounds() {
    var ref = new TypeRef<T[]>() {};
    assertEquals(Dummy1[].class, ref.rawType());
  }

  @Test
  <T extends Dummy1 & Dummy2> void rawType_typeVariable3DArrayTwoBounds() {
    var ref = new TypeRef<T[][][]>() {};
    assertEquals(Dummy1[][][].class, ref.rawType());
  }

  @Test
  <T extends Dummy1 & Dummy2, Y extends T, X extends Y>
  void rawType_typeVariableWithTypeVariableBound() {
    var ref = new TypeRef<X>() {};
    assertEquals(Dummy1.class, ref.rawType());
  }

  @Test
  <T extends Dummy1 & Dummy2, Y extends T, X extends Y>
  void rawType_wildcardWithTypeVariableBound() {
    var wildCard = ((ParameterizedType) new TypeRef<List<? extends X>>() {}.type())
        .getActualTypeArguments()[0];
    var ref = TypeRef.from(wildCard);
    assertTrue(ref.type() instanceof WildcardType);
    assertEquals(Dummy1.class, ref.rawType());
  }

  @Test
  <T extends List<String>> void rawType_typeVariableWithParameterizedTypeBounds() {
    var ref = new TypeRef<T>() {};
    assertEquals(List.class, ref.rawType());
  }

  @Test
  <T extends Dummy1 & Dummy2, Y extends T, X extends Y>
  void rawType_typeVariable3DArrayWithTypeVariableBound() {
    var ref = new TypeRef<X[][][]>() {};
    assertEquals(Dummy1[][][].class, ref.rawType());
  }

  @Test
  void equals_hashCode() {
    var ref1 = new TypeRef<List<String>>() {};
    var ref2 = TypeRef.from(StringList.type);
    assertEquals(ref1, ref1);
    assertEquals(ref1, ref2);
    assertEquals(ref1.hashCode(), ref2.hashCode());
    assertNotEquals(ref2, TypeRef.from(List.class));
    assertNotEquals(ref1, "I'm not a TypeReference, I'm a String!");
  }

  @Test
  void toString_isTypeName() {
    var ref = new TypeRef<List<String>>() {};
    assertEquals(StringList.type.getTypeName(), ref.toString());
  }

  @Test
  void exactRawType_fromRawType() {
    var ref = new TypeRef<String>() {};
    assertEquals(String.class, ref.exactRawType());
  }

  // exceptional behaviour

  @SuppressWarnings("rawtypes") // intentional
  @Test
  void new_rawSubtype() {
    assertIllegalState(() -> new TypeRef() {});
  }

  @Test
  void from_nonstandardTypeSpecialization() {
    assertIllegalArg(() -> TypeRef.from(new Type() {}));
  }

  @Test
  void from_parameterizedTypeNotReturningValidRawType() {
    var fakeParameterizedType = new ParameterizedType() {
      @Override public Type[] getActualTypeArguments() {
        return new Type[] { Object.class };
      }

      @Override public Type getRawType() {
        return new Type() {};
      }

      @Override public Type getOwnerType() {
        return null;
      }
    };
    assertIllegalArg(() -> TypeRef.from(fakeParameterizedType));
  }

  @Test
  <T> void exactRawType_notRaw() {
    assertThrows(IllegalStateException.class, () -> new TypeRef<T>() {}.exactRawType());
    assertThrows(IllegalStateException.class, () -> new TypeRef<List<String>>() {}.exactRawType());
  }

  interface StringList extends List<String> {
    Type type = StringList.class.getGenericInterfaces()[0];
  }

  interface Dummy1 {}

  interface Dummy2 {}

  private static void assertIllegalArg(Executable action) {
    assertThrows(IllegalArgumentException.class, action);
  }

  private static void assertIllegalState(Executable action) {
    assertThrows(IllegalStateException.class, action);
  }
}
