// JNI bridge for the Automix loop mix renderer (native/loopmix/). Every call
// but render() is made while the mix is being set up; render() runs on the
// player's playback thread, inside its audio sink.
//
// Copyright (C) 2026 Kushagra Singh. GPL-3.0-or-later, with the rest of BitChord.
#include <jni.h>

#include <algorithm>
#include <exception>
#include <new>

#include "loopmix/loop_mix_renderer.h"

using bitchord::loopmix::Deck;
using bitchord::loopmix::Envelope;
using bitchord::loopmix::Renderer;

namespace {

Renderer* From(jlong handle) { return reinterpret_cast<Renderer*>(handle); }

Deck* DeckOf(jlong handle, jint index) {
  if (handle == 0 || index < 0 || index > 1) return nullptr;
  return &From(handle)->deck(index);
}

// A C++ failure escaping across extern "C" terminates the VM, and in the
// render path it also skips the release of the pinned Java array. Turn it into
// a Java exception instead; the caller sees a stack rather than a dead process.
void ThrowJava(JNIEnv* env, const std::exception& error) {
  jclass type = env->FindClass("java/lang/RuntimeException");
  if (type != nullptr) env->ThrowNew(type, error.what());
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeCreate(JNIEnv*, jclass, jint sampleRate) {
  return reinterpret_cast<jlong>(new (std::nothrow) Renderer(sampleRate));
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeDestroy(JNIEnv*, jclass, jlong handle) {
  delete From(handle);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetDeck(
    JNIEnv* env, jclass, jlong handle, jint index, jfloatArray left, jfloatArray right,
    jdouble regionStart, jlong loopStart, jlong loopEnd, jdouble releaseAt,
    jdoubleArray knotFrames, jdoubleArray knotPositions, jboolean runOn, jdouble stopAt,
    jdouble preroll, jfloat level) {
  Deck* deck = DeckOf(handle, index);
  if (deck == nullptr) return;
  try {
    const jsize frames = std::min(env->GetArrayLength(left), env->GetArrayLength(right));
    deck->left.resize(frames);
    deck->right.resize(frames);
    env->GetFloatArrayRegion(left, 0, frames, deck->left.data());
    env->GetFloatArrayRegion(right, 0, frames, deck->right.data());
    deck->regionStart = regionStart;
    deck->loopStart = loopStart;
    deck->loopEnd = loopEnd;
    deck->releaseAt = releaseAt;
    const jsize knots = std::min(env->GetArrayLength(knotFrames), env->GetArrayLength(knotPositions));
    deck->knotTimes.resize(knots);
    deck->knotPositions.resize(knots);
    env->GetDoubleArrayRegion(knotFrames, 0, knots, deck->knotTimes.data());
    env->GetDoubleArrayRegion(knotPositions, 0, knots, deck->knotPositions.data());
    deck->runOn = runOn;
    deck->stopAt = stopAt;
    deck->preroll = preroll;
    deck->level = level;
  } catch (const std::exception& error) {
    ThrowJava(env, error);
  }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetEnvelope(
    JNIEnv* env, jclass, jlong handle, jint index, jint kind, jdoubleArray times, jfloatArray values,
    jboolean exponential) {
  Deck* deck = DeckOf(handle, index);
  if (deck == nullptr) return;
  Envelope* envelope = kind == 0 ? &deck->gain : kind == 1 ? &deck->lowDb : kind == 2 ? &deck->highPassHz : nullptr;
  if (envelope == nullptr) return;
  try {
    const jsize points = std::min(env->GetArrayLength(times), env->GetArrayLength(values));
    envelope->times.resize(points);
    envelope->values.resize(points);
    env->GetDoubleArrayRegion(times, 0, points, envelope->times.data());
    env->GetFloatArrayRegion(values, 0, points, envelope->values.data());
    envelope->exponential = exponential;
  } catch (const std::exception& error) {
    ThrowJava(env, error);
  }
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativePrepare(JNIEnv* env, jclass, jlong handle) {
  if (handle == 0) return;
  try {
    From(handle)->prepare();
  } catch (const std::exception& error) {
    ThrowJava(env, error);
  }
}

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeFirstFrame(JNIEnv*, jclass, jlong handle) {
  return handle != 0 ? From(handle)->firstFrame() : 0;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeRender(
    JNIEnv* env, jclass, jlong handle, jlong frame, jfloatArray interleaved, jint offsetFrames, jint frames) {
  if (handle == 0 || frames <= 0 || offsetFrames < 0) return;
  // Two floats per frame, and the caller's window has to fit inside the array:
  // an offset taken straight from Java used to write past the end and corrupt
  // the heap instead of failing.
  const jlong needed = 2LL * (static_cast<jlong>(offsetFrames) + frames);
  if (needed > env->GetArrayLength(interleaved)) {
    jclass type = env->FindClass("java/lang/IllegalArgumentException");
    if (type != nullptr) env->ThrowNew(type, "render window exceeds the interleaved array");
    return;
  }
  // Critical access: no copy of the block, and render() itself never calls back into Java.
  auto* samples = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(interleaved, nullptr));
  if (samples == nullptr) return;
  try {
    From(handle)->render(frame, samples + 2 * offsetFrames, frames);
  } catch (const std::exception& error) {
    // Released before throwing: the pin is not unwound by a C++ exception, and
    // the renderer would keep a pointer into a heap the VM may move.
    env->ReleasePrimitiveArrayCritical(interleaved, samples, 0);
    ThrowJava(env, error);
    return;
  }
  env->ReleasePrimitiveArrayCritical(interleaved, samples, 0);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetFadeOut(JNIEnv*, jclass, jlong handle, jint index,
                                                                        jdouble trackSeconds) {
  Deck* deck = DeckOf(handle, index);
  if (deck == nullptr) return;
  deck->fadeOutAt.store(trackSeconds < 0 ? -1.0
                                         : deck->releasedPositionOf(trackSeconds, From(handle)->sampleRate()));
}

JNIEXPORT jdouble JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeHeard(JNIEnv*, jclass, jlong handle, jint index) {
  Deck* deck = DeckOf(handle, index);
  return deck != nullptr ? deck->heard.load() : 0.0;
}

}  // extern "C"
