/*
 * Touch buttons (Android) - implementation, see virtual_pad.h. Built into libmain.so; the JNI symbols bind by
 * name to io.github.almostdrownedrat.dinotale.TouchNative when SDLActivity loads the library.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "virtual_pad.h"

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

VP_JNI void JNICALL Java_io_github_almostdrownedrat_dinotale_TouchNative_nativeShaderSetPath(JNIEnv* env, jclass, jstring path) {
    const char* p = path != nullptr ? env->GetStringUTFChars(path, nullptr) : nullptr;
    dinotale_shader_set_path(p != nullptr ? p : "");
    if (p != nullptr) {
        env->ReleaseStringUTFChars(path, p);
    }
}
#endif
