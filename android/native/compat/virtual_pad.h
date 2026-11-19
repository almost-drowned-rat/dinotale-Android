/*
 * Touch buttons (Android) - the bridge between TouchControls / TouchOverlay (Java) and the game's N64 input.
 *
 * Based on the original port's virtual pad bridge (deivid22srk, virtual_pad.cpp/.h); since the handheld fork the
 * Java side is TouchOverlay.java (N64 buttons that can be switched on and off, see TouchControls.java). Nothing
 * in the submodules changes: the touch state is kept here in atomics, written by Android's UI thread through JNI
 * (TouchNative.java) and OR-ed into the N64 input by the get_input wrapper in src/main/main.cpp.
 *
 * Bits from Java (TouchOverlay): 0 A, 1 B, 2 Z, 3 L, 4 R, 5 START, 6-9 C up / down / left / right,
 * 10-13 D-pad up / down / left / right.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#pragma once

#include <cstdint>

namespace androidport::virtualpad {

// Merges the touch state into the consolidated N64 input of controller 1 (get_input wrapper, the game's thread).
void merge_input(int controller_num, uint16_t* buttons, float* x, float* y);

// The game's state for the Java side (poll_input wrapper): the game runs (not the launcher), a menu takes input.
void update_game_state(bool game_started, bool menu_open);

} // namespace androidport::virtualpad
