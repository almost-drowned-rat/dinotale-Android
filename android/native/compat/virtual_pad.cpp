/*
 * Touch buttons (Android) - implementation, see virtual_pad.h. Built into libmain.so; the JNI symbols bind by
 * name to io.github.almostdrownedrat.dinotale.TouchNative when SDLActivity loads the library.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "virtual_pad.h"

#include <string>

#include <atomic>
#include <cmath>
#include <jni.h>

namespace androidport::virtualpad {
namespace {

// TouchOverlay bit -> N64 button mask (DEFINE_N64_BUTTON_INPUTS in
// RecompFrontend/recompinput/include/recompinput/input_types.h)
constexpr uint16_t N64_MASK[14] = {
    0x8000, // A
    0x4000, // B
    0x2000, // Z
    0x0020, // L
    0x0010, // R
    0x1000, // START
    0x0008, // C up
    0x0004, // C down
    0x0002, // C left
    0x0001, // C right
    0x0800, // D-pad up
    0x0400, // D-pad down
    0x0200, // D-pad left
    0x0100, // D-pad right
};

std::atomic<uint16_t> g_buttons{0};
std::atomic<float> g_x{0.0f}, g_y{0.0f};
std::atomic<bool> g_started{false}, g_menu{false};

} // namespace

void merge_input(int controller_num, uint16_t* buttons, float* x, float* y) {
    if (controller_num != 0 || buttons == nullptr || x == nullptr || y == nullptr) {
        return;
    }
    // While a menu takes input the game gets nothing from touch (TouchOverlay hides its buttons then anyway).
    if (g_menu.load(std::memory_order_relaxed)) {
        return;
    }
    *buttons = static_cast<uint16_t>(*buttons | g_buttons.load(std::memory_order_relaxed));
    float px = g_x.load(std::memory_order_relaxed), py = g_y.load(std::memory_order_relaxed);
    // the stick with the larger deflection wins (a controller and touch together never fight)
    if (px * px + py * py > (*x) * (*x) + (*y) * (*y)) {
        *x = px;
        *y = py;
    }
}

void update_game_state(bool game_started, bool menu_open) {
    g_started.store(game_started, std::memory_order_relaxed);
    g_menu.store(menu_open, std::memory_order_relaxed);
}

} // namespace androidport::virtualpad

#define VP_JNI extern "C" __attribute__((visibility("default"))) JNIEXPORT

using namespace androidport::virtualpad;

VP_JNI void JNICALL Java_io_github_almostdrownedrat_dinotale_TouchNative_nativeTouchPad(JNIEnv*, jclass, jint bits, jfloat x,
                                                                              jfloat y) {
    uint16_t mask = 0;
    for (int i = 0; i < 14; i++) {
        if (bits & (1 << i)) {
            mask |= N64_MASK[i];
        }
    }
    float len2 = x * x + y * y;
    if (len2 > 1.0f) {
        float inv = 1.0f / std::sqrt(len2);
        x *= inv;
        y *= inv;
    }
    g_buttons.store(mask, std::memory_order_relaxed);
    g_x.store(x, std::memory_order_relaxed);
    g_y.store(-y, std::memory_order_relaxed); // TouchOverlay: down = +y; N64 input: up = +y
}

VP_JNI jboolean JNICALL Java_io_github_almostdrownedrat_dinotale_TouchNative_nativeGameRunning(JNIEnv*, jclass) {
    return g_started.load(std::memory_order_relaxed) ? JNI_TRUE : JNI_FALSE;
}

VP_JNI jboolean JNICALL Java_io_github_almostdrownedrat_dinotale_TouchNative_nativeMenuOpen(JNIEnv*, jclass) {
    return g_menu.load(std::memory_order_relaxed) ? JNI_TRUE : JNI_FALSE;
}

// The shader the menu Back opens chose (ShaderLibrary.java; src/main/display_options.cpp).
#ifdef DT_SHADER_PRESETS
void dinotale_shader_set_path(const char* path);
bool dinotale_shader_compiling();
std::string dinotale_shader_params();
void dinotale_shader_set_param(const char* name, float value);

VP_JNI jstring JNICALL Java_io_github_almostdrownedrat_dinotale_TouchNative_nativeShaderParams(JNIEnv* env, jclass) {
    return env->NewStringUTF(dinotale_shader_params().c_str());
}


// What Settings > Android Options shows beside its buttons: the preset in use and how many of its parameters
// differ from the preset's values.
#include <mutex>
#include <vector>
#include <cstdlib>
namespace {
std::mutex shader_info_mutex;
std::string shader_info_path;
void shader_info_set_path(const char* p) {
    std::scoped_lock lock(shader_info_mutex);
    std::string path = p != nullptr ? p : "";
    if (path != shader_info_path) {
        shader_info_path = path;
    }
}
}

std::string android_shader_name() {
    std::scoped_lock lock(shader_info_mutex);
    if (shader_info_path.empty()) {
        return "Off";
    }
    std::string name = shader_info_path.substr(shader_info_path.find_last_of('/') + 1);
    if (name.size() > 7 && name.compare(name.size() - 7, 7, ".slangp") == 0) {
        name.resize(name.size() - 7);
    }
    return name;
}

std::string android_shader_params_summary() {
    {
        std::scoped_lock lock(shader_info_mutex);
        if (shader_info_path.empty()) {
            return "-";
        }
    }
    // one line per parameter: name, description, value, min, max, step, initial
    const std::string text = dinotale_shader_params();
    int changed = 0, total = 0;
    size_t start = 0;
    while (start < text.size()) {
        size_t end = text.find('\n', start);
        if (end == std::string::npos) end = text.size();
        std::vector<std::string> f;
        size_t i = start;
        while (i <= end) {
            size_t tab = text.find('\t', i);
            if (tab == std::string::npos || tab > end) tab = end;
            f.push_back(text.substr(i, tab - i));
            i = tab + 1;
        }
        if (f.size() >= 7) {
            total++;
            if (std::fabs(std::strtof(f[2].c_str(), nullptr) - std::strtof(f[6].c_str(), nullptr)) > 1e-6f) changed++;
        }
        start = end + 1;
    }
    if (total == 0) return "-";
    return changed == 0 ? std::string("Default") : std::to_string(changed) + " changed";
}

VP_JNI void JNICALL Java_io_github_almostdrownedrat_dinotale_TouchNative_nativeShaderSetParam(JNIEnv* env, jclass, jstring name,
                                                                                         jfloat value) {
    const char* n = env->GetStringUTFChars(name, nullptr);
    dinotale_shader_set_param(n, value);
    env->ReleaseStringUTFChars(name, n);
}

VP_JNI jboolean JNICALL Java_io_github_almostdrownedrat_dinotale_TouchNative_nativeShaderCompiling(JNIEnv*, jclass) {
    return dinotale_shader_compiling() ? JNI_TRUE : JNI_FALSE;
}

VP_JNI void JNICALL Java_io_github_almostdrownedrat_dinotale_TouchNative_nativeShaderSetPath(JNIEnv* env, jclass, jstring path) {
    const char* p = path != nullptr ? env->GetStringUTFChars(path, nullptr) : nullptr;
    dinotale_shader_set_path(p != nullptr ? p : "");
    shader_info_set_path(p);
    if (p != nullptr) {
        env->ReleaseStringUTFChars(path, p);
    }
}
#endif

// FPS / frame time for the menu Back opens (counted in RecompFrontend's draw_hook, android/patches/recompfrontend-frame-stats.patch).
extern "C" void recompui_frame_stats(float out[3]);

VP_JNI jfloatArray JNICALL Java_io_github_almostdrownedrat_dinotale_TouchNative_nativeFrameStats(JNIEnv* env, jclass) {
    float s[3];
    recompui_frame_stats(s);
    jfloatArray a = env->NewFloatArray(3);
    env->SetFloatArrayRegion(a, 0, 3, s);
    return a;
}

// Settings > Android Options: opens the shader pages of the menu Back opens (MainActivity.openShaderMenu:
// 0 = Shader, 1 = Shader parameters). Any thread.
#include <SDL.h>
void android_open_shader_menu(int which) {
    JNIEnv* env = static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv());
    jobject activity = static_cast<jobject>(SDL_AndroidGetActivity());
    if (env == nullptr || activity == nullptr) {
        return;
    }
    jclass cls = env->GetObjectClass(activity);
    jmethodID method = env->GetMethodID(cls, "openShaderMenu", "(I)V");
    if (method != nullptr) {
        env->CallVoidMethod(activity, method, which);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(activity);
}
