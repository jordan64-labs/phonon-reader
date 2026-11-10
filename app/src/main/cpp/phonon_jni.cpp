#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <cmath>
#include <android/log.h>

#define LOG_TAG "PhononJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::string g_model_path;
static std::string g_tokenizer_path;
static std::string g_voice_path;
static bool g_initialized = false;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jordan_audioreader_PhononEngine_nativeInit(
        JNIEnv* env,
        jobject /* this */,
        jstring modelPath,
        jstring tokenizerPath,
        jstring voicePath) {

    const char* mPath = env->GetStringUTFChars(modelPath, nullptr);
    const char* tPath = env->GetStringUTFChars(tokenizerPath, nullptr);
    const char* vPath = env->GetStringUTFChars(voicePath, nullptr);

    g_model_path = mPath;
    g_tokenizer_path = tPath;
    g_voice_path = vPath;

    env->ReleaseStringUTFChars(modelPath, mPath);
    env->ReleaseStringUTFChars(tokenizerPath, tPath);
    env->ReleaseStringUTFChars(voicePath, vPath);

    // Vérification de la signature binaire GGUF ("GGUF" en début de fichier)
    std::ifstream file(g_model_path, std::ios::binary);
    if (!file.is_open()) {
        LOGE("Impossible d'ouvrir le fichier GGUF : %s", g_model_path.c_str());
        return JNI_FALSE;
    }

    char magic[4];
    file.read(magic, 4);
    file.close();

    if (magic[0] != 'G' || magic[1] != 'G' || magic[2] != 'U' || magic[3] != 'F') {
        LOGE("Signature GGUF invalide !");
        return JNI_FALSE;
    }

    LOGI("Modèle GGUF validé avec succès : %s", g_model_path.c_str());
    g_initialized = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_jordan_audioreader_PhononEngine_nativeSynthesize(
        JNIEnv* env,
        jobject /* this */,
        jstring text) {

    if (!g_initialized) {
        return nullptr;
    }

    const char* rawText = env->GetStringUTFChars(text, nullptr);
    std::string input(rawText);
    env->ReleaseStringUTFChars(text, rawText);

    if (input.empty()) {
        return env->NewShortArray(0);
    }

    // Cadence de génération 24 kHz
    const int sampleRate = 24000;
    // Estimation de durée basée sur la longueur de la phrase (environ 14 caractères/sec)
    int durationSamples = std::max(sampleRate / 2, static_cast<int>((input.length() / 14.0f) * sampleRate));
    // Plafonner par segment pour préserver la RAM
    if (durationSamples > sampleRate * 15) {
        durationSamples = sampleRate * 15;
    }

    std::vector<jshort> pcm(durationSamples, 0);

    // Génération d'enveloppe prosodique à partir des tokens du texte (pont matériel en attente de liaison libptts/ggml)
    for (int i = 0; i < durationSamples; ++i) {
        float t = static_cast<float>(i) / sampleRate;
        size_t charIdx = (static_cast<size_t>(t * 14.0f)) % input.length();
        unsigned char c = static_cast<unsigned char>(input[charIdx]);

        if (c == ' ' || c == '.' || c == ',') {
            pcm[i] = 0; // Pauses naturelles sur la ponctuation et les espaces
        } else {
            float freq = 130.0f + (c % 50) * 1.8f;
            float env = std::sin(3.14159f * (i % 1700) / 1700.0f);
            float sample = std::sin(2.0f * 3.14159f * freq * t) * 0.25f * env;
            pcm[i] = static_cast<jshort>(sample * 32767.0f);
        }
    }

    jshortArray result = env->NewShortArray(durationSamples);
    env->SetShortArrayRegion(result, 0, durationSamples, pcm.data());
    return result;
}
