// DinoTale's launcher screen (launcher.cpp).
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include "recompui/recompui.h"

namespace dinotale {
    // From recompui's launcher init callback, after the game options were added.
    void launcher_init(recompui::LauncherMenu *menu, recompui::GameOptionsMenu *options);
    // From recompui's launcher update callback (every frame).
    void launcher_update(recompui::LauncherMenu *menu);
}
