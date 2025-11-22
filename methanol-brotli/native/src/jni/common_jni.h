/* Copyright 2017 Google Inc. All Rights Reserved.

   Distributed under MIT license.
   See file LICENSE for detail or copy at https://opensource.org/licenses/MIT
*/

#ifndef BROTLI_WRAPPER_DEC_DECODER_JNI_H_
#define BROTLI_WRAPPER_DEC_DECODER_JNI_H_

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Set data to be brotli dictionary data.
 *
 * @param buffer direct ByteBuffer
 * @returns false if dictionary data was already set; otherwise true
 */
JNIEXPORT jint JNICALL
Java_com_github_mizosoft_methanol_brotli_internal_vendor_CommonJNI_nativeSetDictionaryData(
    JNIEnv* env, jobject /*jobj*/, jobject buffer);

#ifdef __cplusplus
}
#endif

#endif  // BROTLI_WRAPPER_DEC_DECODER_JNI_H_
