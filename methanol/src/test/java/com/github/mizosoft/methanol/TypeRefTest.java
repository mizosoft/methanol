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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.List;
import org.junit.jupiter.api.Test;

class TypeRefTest {
  @Test
  void new_rawType() {
    var ref = new TypeRef<String>() {};
    assertThat(ref.type()).isEqualTo(String.class);
  }

  @Test
  void new_parameterizedType() {
    var ref = new TypeRef<List<String>>() {};
    assertThat(ref.type()).isEqualTo(StringList.TYPE);
  }

  @Test
  void rawType_rawType() {
    var ref = new TypeRef<String>() {};
    assertThat(ref.rawType()).isEqualTo(String.class);
  }

  @Test
  void rawType_parameterizedType() {
    var ref = new TypeRef<List<String>>() {};
    assertThat(ref.rawType()).isEqualTo(List.class);
  }

  @Test
  <T> void rawType_typeVariableNoBounds() {
    var ref = new TypeRef<T>() {};
    assertThat(ref.rawType()).isEqualTo(Object.class);
  }

  @Test
  <T extends Dummy1> void rawType_typeVariableOneBound() {
    var ref = new TypeRef<T>() {};
    assertThat(ref.rawType()).isEqualTo(Dummy1.class);
  }

  @Test
  <T extends Dummy1 & Dummy2> void rawType_typeVariableTwoBounds() {
    var ref = new TypeRef<T>() {};
    assertThat(ref.rawType()).isEqualTo(Dummy1.class); // Resolution selects the first bound
  }

  @Test
  void rawType_wildcardNoBounds() {
    var wildCard = ((ParameterizedType) new TypeRef<List<?>>() {}.type())
        .getActualTypeArguments()[0];
    var ref = TypeRef.from(wildCard);
    assertThat(ref.type()).isInstanceOf(WildcardType.class);
    assertThat(ref.rawType()).isEqualTo(Object.class);
  }

  @Test
  void rawType_wildcardOneBounds() {
    var wildCard = ((ParameterizedType) new TypeRef<List<? extends Dummy1>>() {}.type())
        .getActualTypeArguments()[0];
    var ref = TypeRef.from(wildCard);
    assertThat(ref.type()).isInstanceOf(WildcardType.class);
    assertThat(ref.rawType()).isEqualTo(Dummy1.class);
  }

  @Test
  void rawType_parameterizedTypeArray() {
    var ref = new TypeRef<List<String>[]>() {};
    assertThat(ref.rawType()).isEqualTo(List[].class);
  }

  @Test
  <T> void rawType_typeVariableArrayNoBounds() {
    var ref = new TypeRef<T[]>() {};
    assertThat(ref.rawType()).isEqualTo(Object[].class);
  }

  @Test
  <T extends Dummy1 & Dummy2> void rawType_typeVariableArrayTwoBounds() {
    var ref = new TypeRef<T[]>() {};
    assertThat(ref.rawType()).isEqualTo(Dummy1[].class);
  }

  @Test
  <T extends Dummy1 & Dummy2> void rawType_typeVariable3DArrayTwoBounds() {
    var ref = new TypeRef<T[][][]>() {};
    assertThat(ref.rawType()).isEqualTo(Dummy1[][][].class);
  }

  @Test
  <T extends Dummy1 & Dummy2, Y extends T, X extends Y>
  void rawType_typeVariableWithTypeVariableBound() {
    var ref = new TypeRef<X>() {};
    assertThat(ref.rawType()).isEqualTo(Dummy1.class);
  }

  @Test
  <T extends Dummy1 & Dummy2, Y extends T, X extends Y>
  void rawType_wildcardWithTypeVariableBound() {
    var wildCard = ((ParameterizedType) new TypeRef<List<? extends X>>() {}.type())
        .getActualTypeArguments()[0];
    var ref = TypeRef.from(wildCard);
    assertThat(ref.type()).isInstanceOf(WildcardType.class);
    assertThat(ref.rawType()).isEqualTo(Dummy1.class);
  }

  @Test
  <T extends List<String>> void rawType_typeVariableWithParameterizedTypeBounds() {
    var ref = new TypeRef<T>() {};
    assertThat(ref.rawType()).isEqualTo(List.class);
  }

  @Test
  <T extends Dummy1 & Dummy2, Y extends T, X extends Y>
  void rawType_typeVariable3DArrayWithTypeVariableBound() {
    var ref = new TypeRef<X[][][]>() {};
    assertThat(ref.rawType()).isEqualTo(Dummy1[][][].class);
  }

  @Test
  void equals_hashCode() {
    var ref1 = new TypeRef<List<String>>() {};
    var ref2 = TypeRef.from(StringList.TYPE);
    assertThat(ref1)
        .isEqualTo(ref1)
        .isEqualTo(ref2)
        .hasSameHashCodeAs(ref2)
        .isNotEqualTo(TypeRef.from(List.class))
        .isNotEqualTo("I'm not a TypeRef, I'm a String!");
  }

  @Test
  void toString_isTypeName() {
    var ref = new TypeRef<List<String>>() {};
    assertThat(ref).hasToString(StringList.TYPE.getTypeName());
  }

  @Test
  void exactRawType_fromRawType() {
    var ref = new TypeRef<String>() {};
    assertThat(ref.exactRawType()).isEqualTo(String.class);
  }

  @SuppressWarnings("rawtypes") // Intentional
  @Test
  void new_rawSubtype() {
    assertThatIllegalStateException().isThrownBy(() -> new TypeRef() {});
  }

  @Test
  void from_nonstandardTypeSpecialization() {
    assertThatIllegalArgumentException().isThrownBy(() -> TypeRef.from(new Type() {}));
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
    assertThatIllegalArgumentException().isThrownBy(() -> TypeRef.from(fakeParameterizedType));
  }

  @Test
  <T> void exactRawType_notRaw() {
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> new TypeRef<T>() {}.exactRawType());
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> new TypeRef<List<String>>() {}.exactRawType());
  }

  interface StringList extends List<String> {
    Type TYPE = StringList.class.getGenericInterfaces()[0];
  }

  interface Dummy1 {}

  interface Dummy2 {}
}
