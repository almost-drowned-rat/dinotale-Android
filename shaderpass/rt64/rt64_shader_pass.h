// RetroArch slang shader presets for RT64's present (lib-patches/rt64/0026): the VI renderer draws the game's
// picture into an image of the swap chain's size, and librashader's Vulkan filter chain draws that image into the
// swap chain. Modelled on THPS 2X Recompiled's shader module (librashader v0.12.1, Vulkan runtime only).
// librashader is loaded at run time (shaderpass_loader.cpp): without it, or without a preset, nothing changes.
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>
#include <filesystem>
#include <memory>
#include <string>

#include "common/rt64_plume.h"
#include "render/rt64_render_worker.h"

namespace RT64 {
    // The preset to use (a .slangp file); an empty path turns the shader off. Any thread.
    void setShaderPreset(const std::filesystem::path &presetPath);

    // A line for the settings: why there is no shader ("librashader wasn't found", the preset's error), or empty.
    std::string getShaderStatus();

    // Whether librashader could be loaded at all (the settings grey the shader choice out without it).
    bool shaderRuntimeAvailable();

    class ShaderPass {
    public:
        ShaderPass();
        ~ShaderPass();

        // True when a preset is selected and its filter chain exists. Builds the chain when the preset changed:
        // librashader's uploads go into the worker's command list on their own and are executed and waited for
        // here (its deferred creation needs an empty command buffer, executed before the first frame), so call
        // this before the frame's command list is begun.
        bool prepare(RenderDevice *device, RenderSwapChain *swapChain, RenderWorker *worker, bool vulkan);

        // The image the VI renderer draws into instead of the swap chain image.
        RenderFramebuffer *sourceFramebuffer() const;
        RenderTexture *sourceTexture() const;

        // Runs the chain: source (SHADER_READ) -> swap chain image (COLOR_WRITE), over the whole image.
        void record(RenderCommandList *commandList, RenderTexture *swapChainTexture);

        void release();

    private:
        struct Impl;
        std::unique_ptr<Impl> impl;
    };
};
