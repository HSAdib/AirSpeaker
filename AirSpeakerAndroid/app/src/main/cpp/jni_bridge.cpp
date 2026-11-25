#include "native_audio.h"
#include <jni.h>
#include <memory>
#include <mutex>
#include <algorithm>
#include <cmath>

static std::shared_ptr<NativeAudioEngine> gAudioEngine;
static std::mutex gEngineMutex;

// Cached settings so UI changes made before nativeStart() are preserved
static float gSavedTargetMs = 30.0f;
static bool gSavedAutoMode = true;
static bool gSavedMuted = false;
static float gSavedVolumeGain = 1.0f;
static bool gSavedLowLatency = true;
static bool gSavedEnableAudioEffects = false;
static bool gSavedPartyRelay = false;
static std::vector<std::string> gSavedPartyPeers;
static bool gSavedEqEnabled = false;
static float gSavedEqBands[5] = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
static bool gSavedBluetoothRoute = false;
static bool gSavedUseExternalOutput = false;
static std::string gSavedAllowedSenderIp = "";
static bool gSavedRequireAllowedSender = false;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeStart(JNIEnv* /*env*/, jobject /*thiz*/, jint port) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gAudioEngine) {
        gAudioEngine = std::make_shared<NativeAudioEngine>();
    }
    gAudioEngine->setTargetBufferMs(gSavedTargetMs);
    gAudioEngine->setAutoMode(gSavedAutoMode);
    gAudioEngine->setMuted(gSavedMuted);
    gAudioEngine->setVolumeGain(gSavedVolumeGain);
    gAudioEngine->setLowLatencyMode(gSavedLowLatency);
    gAudioEngine->setEnableAudioEffects(gSavedEnableAudioEffects);
    gAudioEngine->setPartyRelayEnabled(gSavedPartyRelay);
    gAudioEngine->setPartyPeers(gSavedPartyPeers);
    gAudioEngine->setEqualizerBands(gSavedEqEnabled, gSavedEqBands[0], gSavedEqBands[1], gSavedEqBands[2], gSavedEqBands[3], gSavedEqBands[4]);
    gAudioEngine->setBluetoothRouteActive(gSavedBluetoothRoute, false);
    gAudioEngine->setAllowedSenderIp(gSavedAllowedSenderIp, gSavedRequireAllowedSender);
    return gAudioEngine->start(port, gSavedUseExternalOutput) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::shared_ptr<NativeAudioEngine> engineToStop;
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        engineToStop = std::move(gAudioEngine);
        if (engineToStop) {
            engineToStop->stop();
        }
    }
    // Destruction occurs here only after all in-flight local shared_ptr holders exit.
    engineToStop.reset();
}

JNIEXPORT void JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeSetTargetBufferMs(JNIEnv* /*env*/, jobject /*thiz*/, jfloat targetMs) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gSavedTargetMs = std::isfinite(targetMs) ? std::clamp(targetMs, 10.0f, 200.0f) : 30.0f;
    if (gAudioEngine) {
        gAudioEngine->setTargetBufferMs(gSavedTargetMs);
    }
}

JNIEXPORT jfloat JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeGetTargetBufferMs(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gAudioEngine) {
        return gAudioEngine->getTargetBufferMs();
    }
    return gSavedTargetMs;
}

JNIEXPORT void JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeSetAutoMode(JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gSavedAutoMode = (enabled == JNI_TRUE);
    if (gAudioEngine) {
        gAudioEngine->setAutoMode(gSavedAutoMode);
    }
}

JNIEXPORT void JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeSetMuted(JNIEnv* /*env*/, jobject /*thiz*/, jboolean muted) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gSavedMuted = (muted == JNI_TRUE);
    if (gAudioEngine) {
        gAudioEngine->setMuted(gSavedMuted);
    }
}

JNIEXPORT void JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeSetVolumeGain(JNIEnv* /*env*/, jobject /*thiz*/, jfloat gain) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gSavedVolumeGain = std::isfinite(gain) ? std::clamp(gain, 0.0f, 2.0f) : 1.0f;
    if (gAudioEngine) {
        gAudioEngine->setVolumeGain(gSavedVolumeGain);
    }
}

JNIEXPORT jfloat JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeGetVolumeGain(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gAudioEngine) {
        return gAudioEngine->getVolumeGain();
    }
    return gSavedVolumeGain;
}

JNIEXPORT void JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeSetLowLatencyMode(JNIEnv* /*env*/, jobject /*thiz*/, jboolean lowLatency) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gSavedLowLatency = (lowLatency == JNI_TRUE);
    if (gAudioEngine) {
        gAudioEngine->setLowLatencyMode(gSavedLowLatency);
    }
}

JNIEXPORT void JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeSetEnableAudioEffects(JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gSavedEnableAudioEffects = (enabled == JNI_TRUE);
    if (gAudioEngine) {
        gAudioEngine->setEnableAudioEffects(gSavedEnableAudioEffects);
    }
}

JNIEXPORT void JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeOnAudioRouteChanged(JNIEnv* /*env*/, jobject /*thiz*/, jboolean isBluetooth) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gSavedBluetoothRoute = (isBluetooth == JNI_TRUE);
    if (gAudioEngine) {
        gAudioEngine->setBluetoothRouteActive(gSavedBluetoothRoute, /*forceReopen=*/true);
    }
}

JNIEXPORT void JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeSetPartyRelay(JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gSavedPartyRelay = (enabled == JNI_TRUE);
    if (gAudioEngine) {
        gAudioEngine->setPartyRelayEnabled(gSavedPartyRelay);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeIsPartyRelay(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return gSavedPartyRelay ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeSetPartyPeers(JNIEnv* env, jobject /*thiz*/, jobjectArray peers) {
    if (!peers) return;
    int len = env->GetArrayLength(peers);
    std::vector<std::string> peerList;
    peerList.reserve(len);
    for (int i = 0; i < len; ++i) {
        jstring str = (jstring)env->GetObjectArrayElement(peers, i);
        if (str) {
            const char* chars = env->GetStringUTFChars(str, nullptr);
            if (chars) {
                peerList.emplace_back(chars);
                env->ReleaseStringUTFChars(str, chars);
            }
            env->DeleteLocalRef(str);
        }
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gSavedPartyPeers = peerList;
    if (gAudioEngine) {
        gAudioEngine->setPartyPeers(gSavedPartyPeers);
    }
}

JNIEXPORT void JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeSetEqBands(JNIEnv* env, jobject /*thiz*/, jboolean enabled, jfloatArray gainsDb) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gSavedEqEnabled = (enabled == JNI_TRUE);
    if (gainsDb != nullptr) {
        jsize len = env->GetArrayLength(gainsDb);
        jfloat* elements = env->GetFloatArrayElements(gainsDb, nullptr);
        if (elements != nullptr) {
            for (int i = 0; i < 5; ++i) {
                float raw = (i < len) ? elements[i] : 0.0f;
                gSavedEqBands[i] = std::isfinite(raw) ? std::clamp(raw, -12.0f, 12.0f) : 0.0f;
            }
            env->ReleaseFloatArrayElements(gainsDb, elements, JNI_ABORT);
        }
    }
    if (gAudioEngine) {
        gAudioEngine->setEqualizerBands(gSavedEqEnabled, gSavedEqBands[0], gSavedEqBands[1], gSavedEqBands[2], gSavedEqBands[3], gSavedEqBands[4]);
    }
}

JNIEXPORT jint JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeGetAudioSessionId(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gAudioEngine) {
        return gAudioEngine->getAudioSessionId();
    }
    return 0;
}

JNIEXPORT jfloatArray JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeGetStats(JNIEnv* env, jobject /*thiz*/) {
    jfloatArray result = env->NewFloatArray(9);
    if (!result) return nullptr;

    jfloat statsArr[9] = {0.0f};
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        statsArr[1] = gSavedTargetMs;
        statsArr[7] = gSavedAutoMode ? 1.0f : 0.0f;
        if (gAudioEngine) {
            JitterStats stats = gAudioEngine->getStats();
            statsArr[0] = stats.bufferMs;
            statsArr[1] = stats.targetMs;
            statsArr[2] = static_cast<float>(stats.underruns);
            statsArr[3] = static_cast<float>(stats.lostPackets);
            statsArr[4] = static_cast<float>(stats.latePackets);
            statsArr[5] = static_cast<float>(stats.rxPacketsPerSecond);
            statsArr[6] = static_cast<float>(stats.activeCodec);
            statsArr[7] = stats.autoMode ? 1.0f : 0.0f;
            statsArr[8] = static_cast<float>(stats.rxPackets);
        }
    }

    env->SetFloatArrayRegion(result, 0, 9, statsArr);
    return result;
}

JNIEXPORT jdoubleArray JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeGetAdvancedStats(JNIEnv* env, jobject /*thiz*/) {
    jdoubleArray result = env->NewDoubleArray(16);
    if (!result) return nullptr;

    jdouble statsArr[16] = {0.0};
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        statsArr[1] = static_cast<double>(gSavedTargetMs);
        if (gAudioEngine) {
            AdvancedEngineStats adv = gAudioEngine->getAdvancedStats();
            statsArr[0] = static_cast<double>(adv.bufferMs);
            statsArr[1] = static_cast<double>(adv.targetMs);
            statsArr[2] = static_cast<double>(adv.underruns);
            statsArr[3] = static_cast<double>(adv.lostPackets);
            statsArr[4] = static_cast<double>(adv.latePackets);
            statsArr[5] = static_cast<double>(adv.duplicatePackets);
            statsArr[6] = static_cast<double>(adv.concealedPackets);
            statsArr[7] = static_cast<double>(adv.intentionalDriftDrops);
            statsArr[8] = static_cast<double>(adv.packetQueueOverflowDrops);
            statsArr[9] = static_cast<double>(adv.pcmRingOverflowDrops);
            statsArr[10] = static_cast<double>(adv.pcmRingUnderrunFrames);
            statsArr[11] = static_cast<double>(adv.pcmRingAvailableFrames);
            statsArr[12] = adv.callbackAverageMicros;
            statsArr[13] = static_cast<double>(adv.callbackMaxMicros);
            statsArr[14] = static_cast<double>(adv.callbackDeadlineMisses);
            statsArr[15] = adv.rxPps;
        }
    }

    env->SetDoubleArrayRegion(result, 0, 16, statsArr);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeGetSenderIp(JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gAudioEngine) {
        std::string ip = gAudioEngine->getSenderIp();
        if (!ip.empty()) {
            return env->NewStringUTF(ip.c_str());
        }
    }
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeSetUseExternalOutput(JNIEnv* /*env*/, jobject /*thiz*/, jboolean useExternal) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gSavedUseExternalOutput = (useExternal == JNI_TRUE);
    if (gAudioEngine) {
        gAudioEngine->setUseExternalOutput(gSavedUseExternalOutput);
    }
}

JNIEXPORT jint JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeReadExternalPcm(JNIEnv* env, jobject /*thiz*/, jshortArray outBuffer, jint numFrames) {
    if (!outBuffer || numFrames <= 0) return 0;
    jsize len = env->GetArrayLength(outBuffer);
    if (len < numFrames * 2) return 0;

    std::shared_ptr<NativeAudioEngine> engine;
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        engine = gAudioEngine;
    }
    if (!engine) return 0;

    jshort* elems = env->GetShortArrayElements(outBuffer, nullptr);
    if (!elems) return 0;
    int32_t rendered = engine->renderExternalAudio(reinterpret_cast<int16_t*>(elems), numFrames);
    env->ReleaseShortArrayElements(outBuffer, elems, 0);
    return rendered;
}

JNIEXPORT void JNICALL
Java_com_hasan_airspeaker_AudioNative_nativeSetAllowedSenderIp(JNIEnv* env, jobject /*thiz*/, jstring ip, jboolean requireMatch) {
    std::string ipStr;
    if (ip != nullptr) {
        const char* chars = env->GetStringUTFChars(ip, nullptr);
        if (chars != nullptr) {
            ipStr = chars;
            env->ReleaseStringUTFChars(ip, chars);
        }
    }
    std::lock_guard<std::mutex> lock(gEngineMutex);
    gSavedAllowedSenderIp = ipStr;
    gSavedRequireAllowedSender = (requireMatch == JNI_TRUE);
    if (gAudioEngine) {
        gAudioEngine->setAllowedSenderIp(gSavedAllowedSenderIp, gSavedRequireAllowedSender);
    }
}

} // extern "C"
