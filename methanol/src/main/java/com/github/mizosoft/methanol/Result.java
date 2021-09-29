/*
 * Copyright (c) 2021 d3coder
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

import java.util.Optional;

public class Result<T, E> {
    private final T response;
    private final E error;

    private Result(T response, E error) {
        this.response = response;
        this.error = error;
    }

    public Optional<T> getResponse() {
        return Optional.ofNullable(response);
    }

    public Optional<E> getError() {
        return Optional.ofNullable(error);
    }

    public static <T, E> Result<T, E> ofError(E e) {
        return new Result<>(null, e);
    }

    public static <T, E> Result<T, E> ofResponse(T t) {
        return new Result<>(t, null);
    }
}
