#include <jni.h>
#include <lame.h>
#include <stdint.h>
#include <stdlib.h>

typedef struct {
    lame_global_flags *gfp;
    int channels;
} NhgMp3Handle;

JNIEXPORT jlong JNICALL
Java_ai_nhg_capturepoc_Mp3Encoder_nativeCreate(
        JNIEnv *env, jobject thiz, jint sampleRate, jint channels,
        jint bitrateKbps, jint quality) {
    (void) env;
    (void) thiz;
    if (sampleRate < 8000 || sampleRate > 48000) return 0;
    if (channels < 1 || channels > 2) return 0;
    if (bitrateKbps < 32 || bitrateKbps > 320) return 0;
    if (quality < 0 || quality > 9) return 0;

    lame_global_flags *gfp = lame_init();
    if (gfp == NULL) return 0;
    if (lame_set_in_samplerate(gfp, sampleRate) < 0 ||
        lame_set_num_channels(gfp, channels) < 0 ||
        lame_set_brate(gfp, bitrateKbps) < 0 ||
        lame_set_quality(gfp, quality) < 0) {
        lame_close(gfp);
        return 0;
    }
    lame_set_mode(gfp, channels == 2 ? STEREO : MONO);
    lame_set_write_id3tag_automatic(gfp, 0);
    if (lame_init_params(gfp) < 0) {
        lame_close(gfp);
        return 0;
    }

    NhgMp3Handle *handle = (NhgMp3Handle *) calloc(1, sizeof(NhgMp3Handle));
    if (handle == NULL) {
        lame_close(gfp);
        return 0;
    }
    handle->gfp = gfp;
    handle->channels = channels;
    return (jlong) (intptr_t) handle;
}

JNIEXPORT jint JNICALL
Java_ai_nhg_capturepoc_Mp3Encoder_nativeEncode(
        JNIEnv *env, jobject thiz, jlong handle, jshortArray pcm,
        jint frames, jbyteArray out) {
    (void) thiz;
    NhgMp3Handle *h = (NhgMp3Handle *) (intptr_t) handle;
    if (h == NULL || h->gfp == NULL || pcm == NULL || out == NULL || frames <= 0) return -1;

    jsize pcmLen = (*env)->GetArrayLength(env, pcm);
    jsize outLen = (*env)->GetArrayLength(env, out);
    if (pcmLen < frames * h->channels) return -2;
    if (outLen <= 0) return -3;

    jshort *pcmPtr = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (pcmPtr == NULL) return -4;
    jbyte *outPtr = (*env)->GetByteArrayElements(env, out, NULL);
    if (outPtr == NULL) {
        (*env)->ReleaseShortArrayElements(env, pcm, pcmPtr, JNI_ABORT);
        return -5;
    }

    int written = lame_encode_buffer_interleaved(
            h->gfp, pcmPtr, frames, (unsigned char *) outPtr, outLen);

    (*env)->ReleaseShortArrayElements(env, pcm, pcmPtr, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, outPtr, 0);
    return (jint) written;
}

JNIEXPORT jint JNICALL
Java_ai_nhg_capturepoc_Mp3Encoder_nativeFlush(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray out) {
    (void) thiz;
    NhgMp3Handle *h = (NhgMp3Handle *) (intptr_t) handle;
    if (h == NULL || h->gfp == NULL || out == NULL) return -1;

    jsize outLen = (*env)->GetArrayLength(env, out);
    if (outLen <= 0) return -2;

    jbyte *outPtr = (*env)->GetByteArrayElements(env, out, NULL);
    if (outPtr == NULL) return -3;

    int written = lame_encode_flush(h->gfp, (unsigned char *) outPtr, outLen);

    (*env)->ReleaseByteArrayElements(env, out, outPtr, 0);
    return (jint) written;
}

JNIEXPORT void JNICALL
Java_ai_nhg_capturepoc_Mp3Encoder_nativeClose(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    NhgMp3Handle *h = (NhgMp3Handle *) (intptr_t) handle;
    if (h == NULL) return;
    if (h->gfp != NULL) lame_close(h->gfp);
    free(h);
}

JNIEXPORT jstring JNICALL
Java_ai_nhg_capturepoc_Mp3Encoder_nativeVersion(
        JNIEnv *env, jobject thiz) {
    (void) thiz;
    return (*env)->NewStringUTF(env, get_lame_version());
}
