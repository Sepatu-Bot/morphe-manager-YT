#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstring>
#include <ctime>
#include <unistd.h>
#include <queue>
#include <mutex>

#include "doom/doomgeneric.h"

#define LOG_TAG "DoomJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static pixel_t* sFrameBuffer = nullptr;
static bool sNewFrame = false;
static std::mutex sFrameMutex;

static std::queue<std::pair<int, unsigned char>> sKeyQueue;
static std::mutex sKeyMutex;

// ---- doomgeneric platform layer ----

void DG_Init() {
    sFrameBuffer = new pixel_t[DOOMGENERIC_RESX * DOOMGENERIC_RESY];
    LOGD("DG_Init %dx%d", DOOMGENERIC_RESX, DOOMGENERIC_RESY);
}

void DG_DrawFrame() {
    std::lock_guard<std::mutex> lock(sFrameMutex);
    const int count = DOOMGENERIC_RESX * DOOMGENERIC_RESY;
    auto* src = reinterpret_cast<const uint32_t*>(DG_ScreenBuffer);
    for (int i = 0; i < count; i++) {
        uint32_t p = src[i];
        uint32_t r = (p >> 16) & 0xff;
        uint32_t g = (p >> 8)  & 0xff;
        uint32_t b =  p        & 0xff;
        // doomgeneric outputs 0x00RRGGBB; Android RGBA_8888 stores [R,G,B,A] in memory
        sFrameBuffer[i] = 0xff000000u | (b << 16) | (g << 8) | r;
    }
    sNewFrame = true;
}

void DG_SleepMs(uint32_t ms) {
    usleep(ms * 1000);
}

uint32_t DG_GetTicksMs() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

int DG_GetKey(int* pressed, unsigned char* doomKey) {
    std::lock_guard<std::mutex> lock(sKeyMutex);
    if (sKeyQueue.empty()) return 0;
    auto [p, k] = sKeyQueue.front();
    sKeyQueue.pop();
    *pressed = p;
    *doomKey = k;
    return 1;
}

void DG_SetWindowTitle(const char* title) {
    LOGD("Doom title: %s", title);
}

// ---- JNI interface ----

extern "C" {

JNIEXPORT void JNICALL
Java_app_morphe_manager_ui_screen_patcher_game_DoomLib_init(
        JNIEnv* env, jobject, jstring wadPath) {
    const char* path = env->GetStringUTFChars(wadPath, nullptr);

    static char arg0[] = "doom";
    static char arg1[] = "-iwad";
    static char arg2[512];
    strncpy(arg2, path, sizeof(arg2) - 1);
    env->ReleaseStringUTFChars(wadPath, path);

    // Must be static - doomgeneric stores myargv pointer and reads it on every tick
    static char* argv[] = { arg0, arg1, arg2 };
    doomgeneric_Create(3, argv);
}

JNIEXPORT void JNICALL
Java_app_morphe_manager_ui_screen_patcher_game_DoomLib_tick(
        JNIEnv*, jobject) {
    doomgeneric_Tick();
}

// Copies the latest frame into the provided Bitmap; returns true if a new frame was available
JNIEXPORT jboolean JNICALL
Java_app_morphe_manager_ui_screen_patcher_game_DoomLib_copyFrame(
        JNIEnv* env, jobject, jobject bitmap) {
    std::lock_guard<std::mutex> lock(sFrameMutex);
    if (!sNewFrame || !sFrameBuffer) return JNI_FALSE;

    void* pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return JNI_FALSE;
    memcpy(pixels, sFrameBuffer,
           DOOMGENERIC_RESX * DOOMGENERIC_RESY * sizeof(uint32_t));
    AndroidBitmap_unlockPixels(env, bitmap);

    sNewFrame = false;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_app_morphe_manager_ui_screen_patcher_game_DoomLib_sendKey(
        JNIEnv*, jobject, jint pressed, jint doomKey) {
    std::lock_guard<std::mutex> lock(sKeyMutex);
    sKeyQueue.emplace(pressed, (unsigned char)doomKey);
}

} // extern "C"
