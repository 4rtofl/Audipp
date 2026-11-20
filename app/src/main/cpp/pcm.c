/* SPDX-License-Identifier: MPL-2.0 */
#include <jni.h>
#include <float.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

enum {
    PROCESSED = 0,
    INVALID_BLOCK = 1,
    NON_FINITE_SAMPLE = 2,
    MAX_SAMPLES = 384000 * 160 / 1000 * 6,
};

_Static_assert(sizeof(float) == 4 && FLT_RADIX == 2 && FLT_MANT_DIG == 24 &&
               FLT_MAX_EXP == 128, "PCM requires IEEE-754 binary32");

static uint8_t *direct_range(JNIEnv *env, jobject buffer, jint offset, size_t bytes) {
    if (buffer == NULL || offset < 0) return NULL;
    const jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    if (capacity < offset || bytes > (uint64_t) (capacity - offset)) return NULL;
    uint8_t *base = (*env)->GetDirectBufferAddress(env, buffer);
    return base == NULL ? NULL : base + offset;
}

static uint32_t read_le(const uint8_t *input, int width) {
    uint32_t bits = 0;
    for (int i = 0; i < width; i++) {
        bits |= (uint32_t) input[i] << (8 * i);
    }
    return bits;
}

static void write_float(uint8_t *output, float sample) {
    uint32_t bits;
    memcpy(&bits, &sample, sizeof(bits));
    for (int i = 0; i < 4; i++) {
        output[i] = (uint8_t) (bits >> (8 * i));
    }
}

static int overlaps(const uint8_t *input, size_t input_bytes,
                    const uint8_t *output, size_t output_bytes) {
    const uintptr_t src = (uintptr_t) input;
    const uintptr_t dst = (uintptr_t) output;
    return src <= dst ? dst - src < input_bytes : src - dst < output_bytes;
}

JNIEXPORT jint JNICALL Java_com_audipp_audio_pcm_PcmJni_process(
        JNIEnv *env, jobject instance, jobject input, jint input_offset,
        jobject output, jint output_offset, jint sample_count, jint sample_code) {
    (void) instance;
    if (sample_count <= 0 || sample_count > MAX_SAMPLES ||
        sample_code < -4 || sample_code > 4 || sample_code == -1 || sample_code == 1) {
        return INVALID_BLOCK;
    }

    // Negative codes preserve packed integer bytes. Zero denotes Float32 input.
    const int input_width = sample_code == 0 ? 4 : abs(sample_code);
    const size_t input_bytes = (size_t) sample_count * input_width;
    const size_t output_bytes = sample_code < 0 ? input_bytes : (size_t) sample_count * 4;
    const uint8_t *src = direct_range(env, input, input_offset, input_bytes);
    uint8_t *dst = direct_range(env, output, output_offset, output_bytes);
    if (src == NULL || dst == NULL) return INVALID_BLOCK;

    if (sample_code < 0) {
        if (src != dst) memmove(dst, src, input_bytes);
        return PROCESSED;
    }
    if (sample_code == 0) {
        for (jint i = 0; i < sample_count; i++) {
            if ((read_le(src + i * 4, 4) & 0x7f800000u) == 0x7f800000u) {
                return NON_FINITE_SAMPLE;
            }
        }
        if (src != dst) memmove(dst, src, input_bytes);
        return PROCESSED;
    }
    if (overlaps(src, input_bytes, dst, output_bytes)) return INVALID_BLOCK;

    // Decode every Int32 exactly before rounding once to Float32.
    const uint64_t range = UINT64_C(1) << (input_width * 8);
    const double scale = 2.0 / (double) range;
    for (jint i = 0; i < sample_count; i++) {
        const uint32_t bits = read_le(src + i * input_width, input_width);
        const double value = bits < range / 2 ? (double) bits : (double) bits - (double) range;
        write_float(dst + i * 4, (float) (value * scale));
    }
    return PROCESSED;
}
