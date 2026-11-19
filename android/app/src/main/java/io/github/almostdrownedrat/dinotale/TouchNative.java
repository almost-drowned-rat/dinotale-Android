// TouchNative - the JNI side of the touch buttons (android/native/compat/virtual_pad.cpp in libmain.so).
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.almostdrownedrat.dinotale;

final class TouchNative {
    private TouchNative() { }

    /** TouchOverlay's button bits and its stick (-1..1, y down = +). */
    static native void nativeTouchPad(int buttons, float x, float y);
    /** The game runs (not the launcher screen). */
    static native boolean nativeGameRunning();
    /** One of the game's menus takes input (options menu, launcher dialogs). */
    static native boolean nativeMenuOpen();
    /** The RetroArch shader preset to draw with (a .slangp path; "" for none). */
    static native void nativeShaderSetPath(String path);
    /** RT64 is building (compiling) the preset's filter chain right now. */
    static native boolean nativeShaderCompiling();
    /** The preset's parameters (name, description, value, min, max, step per line, tabs); setting one. */
    static native String nativeShaderParams();
    static native void nativeShaderSetParam(String name, float value);
}
