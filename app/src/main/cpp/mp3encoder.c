/*
 * TuneGrab — wrapper JNI em torno do LAME (libmp3lame 3.100, LGPL).
 * Pipeline: MediaCodec decodifica o áudio do YouTube (M4A/Opus) para PCM 16-bit
 * e este encoder comprime em MP3 CBR, tudo no próprio dispositivo.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "lame.h"

#define MP3_BUF_EXTRA 7200

JNIEXPORT jlong JNICALL
Java_com_tunegrab_app_audio_Mp3Encoder_nativeInit(
        JNIEnv *env, jclass clazz,
        jint sampleRate, jint channels, jint kbps,
        jstring title, jstring artist) {
    lame_global_flags *gfp = lame_init();
    if (gfp == NULL) {
        return 0;
    }
    lame_set_in_samplerate(gfp, (int) sampleRate);
    lame_set_num_channels(gfp, (int) channels);
    lame_set_brate(gfp, (int) kbps);
    lame_set_quality(gfp, 2);          /* alta qualidade de encode */
    lame_set_bWriteVbrTag(gfp, 0);     /* CBR puro, sem header Xing/VBR */

    if (title != NULL) {
        const char *t = (*env)->GetStringUTFChars(env, title, NULL);
        if (t != NULL) {
            id3tag_set_title(gfp, t);
            (*env)->ReleaseStringUTFChars(env, title, t);
        }
    }
    if (artist != NULL) {
        const char *a = (*env)->GetStringUTFChars(env, artist, NULL);
        if (a != NULL) {
            id3tag_set_artist(gfp, a);
            (*env)->ReleaseStringUTFChars(env, artist, a);
        }
    }

    if (lame_init_params(gfp) < 0) {
        lame_close(gfp);
        return 0;
    }
    return (jlong) (intptr_t) gfp;
}

JNIEXPORT jbyteArray JNICALL
Java_com_tunegrab_app_audio_Mp3Encoder_nativeEncode(
        JNIEnv *env, jclass clazz,
        jlong handle, jshortArray pcm, jint frames) {
    lame_global_flags *gfp = (lame_global_flags *) (intptr_t) handle;
    if (gfp == NULL || frames <= 0) {
        return NULL;
    }

    jsize totalShorts = (*env)->GetArrayLength(env, pcm);
    if ((jint) totalShorts < frames * lame_get_num_channels(gfp)) {
        return NULL;
    }

    int bufSize = (int) (1.25 * frames) + MP3_BUF_EXTRA;
    unsigned char *buf = (unsigned char *) malloc((size_t) bufSize);
    if (buf == NULL) {
        return NULL;
    }

    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    int written = lame_encode_buffer_interleaved(
            gfp, in, (int) frames, buf, bufSize);
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);

    if (written < 0) {
        free(buf);
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, written);
    (*env)->SetByteArrayRegion(env, result, 0, written, (jbyte *) buf);
    free(buf);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_tunegrab_app_audio_Mp3Encoder_nativeFlush(
        JNIEnv *env, jclass clazz, jlong handle) {
    lame_global_flags *gfp = (lame_global_flags *) (intptr_t) handle;
    if (gfp == NULL) {
        return NULL;
    }
    int bufSize = MP3_BUF_EXTRA * 2;
    unsigned char *buf = (unsigned char *) malloc((size_t) bufSize);
    if (buf == NULL) {
        return NULL;
    }
    int written = lame_encode_flush(gfp, buf, bufSize);
    if (written < 0) {
        free(buf);
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, written);
    (*env)->SetByteArrayRegion(env, result, 0, written, (jbyte *) buf);
    free(buf);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_tunegrab_app_audio_Mp3Encoder_nativeClose(
        JNIEnv *env, jclass clazz, jlong handle) {
    lame_global_flags *gfp = (lame_global_flags *) (intptr_t) handle;
    if (gfp == NULL) {
        return -1;
    }
    int ret = lame_close(gfp);
    return (jint) ret;
}
