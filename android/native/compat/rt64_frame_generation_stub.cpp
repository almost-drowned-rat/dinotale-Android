// RT64 frame generation on Android: a stub. DinoTale's desktop builds offer Lossless Scaling's frame generation
// (lib/lsfg, lib-patches/rt64/0025), which needs the player's Windows Lossless.dll - not available on Android, and its
// setting is left out of the Android build. RT64's present queue still calls these; prepare() never succeeds, so the
// normal present path is always taken.
// SPDX-License-Identifier: GPL-3.0-or-later
#include "rt64_frame_generation.h"

namespace RT64 {
    void setFrameGeneration(const FrameGenerationSettings &) {}
    std::string getFrameGenerationStatus() { return "Not available on Android"; }

    struct FrameGeneration::Impl {};
    FrameGeneration::FrameGeneration() = default;
    FrameGeneration::~FrameGeneration() = default;
    bool FrameGeneration::prepare(RenderDevice *, RenderSwapChain *, bool) { return false; }
    RenderFramebuffer *FrameGeneration::sourceFramebuffer() const { return nullptr; }
    RenderTexture *FrameGeneration::sourceTexture() const { return nullptr; }
    uint32_t FrameGeneration::record(RenderCommandList *) { return 0; }
    bool FrameGeneration::sceneCut() { return false; }
    void FrameGeneration::recordOutput(RenderCommandList *, uint32_t, RenderTexture *) {}
    std::chrono::nanoseconds FrameGeneration::markGameFrame(uint32_t) { return std::chrono::nanoseconds(0); }
    void FrameGeneration::release() {}
};
