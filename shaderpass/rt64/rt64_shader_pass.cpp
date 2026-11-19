// RetroArch slang shader presets for RT64's present, see rt64_shader_pass.h.
// SPDX-License-Identifier: GPL-3.0-or-later
#include <algorithm>
#include <cmath>
#include <string>
#include <vector>
#include <map>
#include <atomic>
#include "rt64_shader_pass.h"

#include <cstdio>
#include <mutex>

#if defined(_WIN32)
#   define WIN32_LEAN_AND_MEAN
#   include <windows.h>
#else
#   include <dlfcn.h>
#   include <unistd.h>
#endif

#include "plume_vulkan.h"

#define LIBRA_RUNTIME_VULKAN
#include "librashader.h"

namespace plume {
    // plume_vulkan.cpp's own conversion (not in its header). VulkanTexture::imageFormat isn't set for every image
    // (the swap chain's stay VK_FORMAT_UNDEFINED), so the format comes from the texture's description.
    VkFormat toVk(RenderFormat format);
}

namespace RT64 {
    namespace {
        // ------------------------------------------------------------ librashader, loaded at run time
        struct Librashader {
            bool tried = false;
            bool loaded = false;
            PFN_libra_instance_abi_version abiVersion = nullptr;
            PFN_libra_preset_create presetCreate = nullptr;
            PFN_libra_preset_free presetFree = nullptr;
            PFN_libra_vk_filter_chain_create_deferred chainCreateDeferred = nullptr;
            PFN_libra_vk_filter_chain_frame chainFrame = nullptr;
            PFN_libra_vk_filter_chain_free chainFree = nullptr;
            PFN_libra_error_write errorWrite = nullptr;
            PFN_libra_error_free_string errorFreeString = nullptr;
            PFN_libra_error_free errorFree = nullptr;
            PFN_libra_preset_get_runtime_params getParams = nullptr;
            PFN_libra_preset_free_runtime_params freeParams = nullptr;
            PFN_libra_preset_set_param presetSetParam = nullptr;
            PFN_libra_vk_filter_chain_set_param chainSetParam = nullptr;
        };

        // The parameters (#pragma parameter) of the built preset, and the values the player set for it.
        struct Param {
            std::string name, description;
            float initial, minimum, maximum, step;
        };

        Librashader libra;
        std::mutex settingsMutex;
        std::filesystem::path wantedPreset;
        uint64_t wantedGeneration = 0;
        std::string status;
        std::vector<Param> presetParams;                       // settingsMutex
        std::map<std::string, float> paramOverrides;           // settingsMutex
        bool paramsDirty = false;                              // settingsMutex

        void setStatus(const std::string &text) {
            std::scoped_lock lock(settingsMutex);
            if (text != status) {
                status = text;
                if (!text.empty()) {
                    fprintf(stderr, "[Shader] %s\n", text.c_str());
                }
            }
        }

#   if defined(_WIN32)
        void *openLibrary() {
            wchar_t exe[MAX_PATH];
            DWORD n = GetModuleFileNameW(nullptr, exe, MAX_PATH);
            std::filesystem::path dll = std::filesystem::path(std::wstring(exe, n)).parent_path() / "librashader.dll";
            return (void *)LoadLibraryW(dll.c_str());
        }
        void *symbol(void *library, const char *name) { return (void *)GetProcAddress((HMODULE)library, name); }
#   else
        void *openLibrary() {
#       if defined(__ANDROID__)
            // packaged in the APK's native libraries, next to this library (libmain.so)
            void *handle = dlopen("liblibrashader_capi.so", RTLD_NOW | RTLD_LOCAL);
            if (handle == nullptr) {
                fprintf(stderr, "[Shader] dlopen liblibrashader_capi.so: %s\n", dlerror());
                Dl_info self = {};
                if ((dladdr((void *)&openLibrary, &self) != 0) && (self.dli_fname != nullptr)) {
                    const std::filesystem::path lib = std::filesystem::path(self.dli_fname).parent_path() / "liblibrashader_capi.so";
                    handle = dlopen(lib.c_str(), RTLD_NOW | RTLD_LOCAL);
                    if (handle == nullptr) {
                        fprintf(stderr, "[Shader] dlopen %s: %s\n", lib.c_str(), dlerror());
                    }
                }
            }
            return handle;
#       else
            // next to the executable
            char exe[4096];
            ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
            if (n > 0) {
                exe[n] = '\0';
                std::filesystem::path lib = std::filesystem::path(exe).parent_path() / "librashader.so";
                if (void *handle = dlopen(lib.c_str(), RTLD_NOW | RTLD_LOCAL)) {
                    return handle;
                }
            }
            return dlopen("librashader.so", RTLD_NOW | RTLD_LOCAL);
#       endif
        }
        void *symbol(void *library, const char *name) { return dlsym(library, name); }
#   endif

        bool loadLibrashader() {
            if (libra.tried) {
                return libra.loaded;
            }
            libra.tried = true;
            void *library = openLibrary();
            if (library == nullptr) {
                return false;
            }
#       define LIBRA_LOAD(field, name) libra.field = reinterpret_cast<decltype(libra.field)>(symbol(library, name))
            LIBRA_LOAD(abiVersion, "libra_instance_abi_version");
            LIBRA_LOAD(presetCreate, "libra_preset_create");
            LIBRA_LOAD(presetFree, "libra_preset_free");
            LIBRA_LOAD(chainCreateDeferred, "libra_vk_filter_chain_create_deferred");
            LIBRA_LOAD(chainFrame, "libra_vk_filter_chain_frame");
            LIBRA_LOAD(chainFree, "libra_vk_filter_chain_free");
            LIBRA_LOAD(errorWrite, "libra_error_write");
            LIBRA_LOAD(errorFreeString, "libra_error_free_string");
            LIBRA_LOAD(errorFree, "libra_error_free");
            LIBRA_LOAD(getParams, "libra_preset_get_runtime_params");
            LIBRA_LOAD(freeParams, "libra_preset_free_runtime_params");
            LIBRA_LOAD(presetSetParam, "libra_preset_set_param");
            LIBRA_LOAD(chainSetParam, "libra_vk_filter_chain_set_param");
#       undef LIBRA_LOAD
            libra.loaded = libra.abiVersion && libra.presetCreate && libra.presetFree && libra.chainCreateDeferred &&
                libra.chainFrame && libra.chainFree && libra.errorWrite && libra.errorFreeString && libra.errorFree &&
                (libra.abiVersion() == LIBRASHADER_CURRENT_ABI);
            if (!libra.loaded) {
                fprintf(stderr, "[Shader] librashader found, but it is not the version this build expects (ABI %d)\n",
                        LIBRASHADER_CURRENT_ABI);
            }
            return libra.loaded;
        }

        // librashader's error as text, and the error freed.
        std::string errorText(libra_error_t error) {
            if (error == nullptr) {
                return std::string();
            }
            std::string text = "librashader error";
            char *message = nullptr;
            if ((libra.errorWrite(error, &message) == 0) && (message != nullptr)) {
                text = message;
                libra.errorFreeString(&message);
            }
            libra.errorFree(&error);
            return text;
        }
    }

    void setShaderPreset(const std::filesystem::path &presetPath) {
        std::scoped_lock lock(settingsMutex);
        if (presetPath != wantedPreset) {
            wantedPreset = presetPath;
            wantedGeneration++;
            paramOverrides.clear();
            presetParams.clear();
        }
    }

    std::string getShaderParams() {
        std::scoped_lock lock(settingsMutex);
        std::string out;
        char num[160];
        for (const Param &p : presetParams) {
            auto it = paramOverrides.find(p.name);
            float v = it != paramOverrides.end() ? it->second : p.initial;
            snprintf(num, sizeof num, "\t%g\t%g\t%g\t%g\t%g\n", v, p.minimum, p.maximum, p.step, p.initial);
            out += p.name + "\t" + p.description + num;
        }
        return out;
    }

    void setShaderParam(const std::string &name, float value) {
        std::scoped_lock lock(settingsMutex);
        paramOverrides[name] = value;
        paramsDirty = true;
    }

    std::string getShaderStatus() {
        std::scoped_lock lock(settingsMutex);
        return status;
    }

    static std::atomic<bool> compilingNow{ false };
    static std::atomic<int> resolutionMode{ 0 };

    void setShaderResolution(int mode) {
        resolutionMode = mode;
    }

    bool shaderCompiling() {
        return compilingNow.load();
    }

    bool shaderRuntimeAvailable() {
        return loadLibrashader();
    }

    // ---------------------------------------------------------------- the pass

    struct ShaderPass::Impl {
        uint64_t builtGeneration = UINT64_MAX;
        libra_vk_filter_chain_t chain = nullptr;
        bool failed = false;
        size_t frameCount = 0;
        RenderDevice *device = nullptr;
        uint32_t width = 0;          // the swap chain's
        uint32_t height = 0;
        uint32_t sourceWidth = 0;    // the game's picture for the shader (Shader Resolution)
        uint32_t sourceHeight = 0;
        RenderFormat format = RenderFormat::UNKNOWN;
        VkFormat outputFormat = VK_FORMAT_UNDEFINED;
        std::unique_ptr<RenderTexture> source;
        std::unique_ptr<RenderFramebuffer> sourceFramebuffer;

        void freeChain() {
            if (chain != nullptr) {
                libra.chainFree(&chain);
                chain = nullptr;
            }
        }
    };

    ShaderPass::ShaderPass() : impl(std::make_unique<Impl>()) {}

    ShaderPass::~ShaderPass() {
        release();
    }

    bool ShaderPass::prepare(RenderDevice *device, RenderSwapChain *swapChain, RenderWorker *worker, bool vulkan) {
        std::filesystem::path preset;
        uint64_t generation;
        {
            std::scoped_lock lock(settingsMutex);
            preset = wantedPreset;
            generation = wantedGeneration;
        }

        if (generation != impl->builtGeneration) {
            // another preset (or off): drop the old chain, try the new one once
            impl->freeChain();
            impl->failed = false;
            impl->builtGeneration = generation;
            impl->frameCount = 0;
            if (preset.empty()) {
                setStatus(std::string());
            }
        }

        if (preset.empty() || impl->failed) {
            return false;
        }

        if (!vulkan) {
            impl->failed = true;
            setStatus("Shaders need the Vulkan graphics API.");
            return false;
        }

        if (!loadLibrashader()) {
            impl->failed = true;
            setStatus("Shaders need librashader, which this build doesn't have.");
            return false;
        }

        const uint32_t width = swapChain->getWidth();
        const uint32_t height = swapChain->getHeight();
        const RenderFormat format = static_cast<VulkanTexture *>(swapChain->getTexture(0))->desc.format;
        if ((width == 0) || (height == 0)) {
            return false;
        }

        // The swap chain images' real format: plume's texture descriptions keep the requested one, also when the
        // surface didn't offer it (Android: B8G8R8A8 asked, R8G8B8A8 used).
        impl->outputFormat = static_cast<VulkanSwapChain *>(swapChain)->pickedSurfaceFormat.format;

        // The game's picture is drawn into an image of the swap chain's format, at the N64's 240 lines (or twice that)
        // with the window's aspect ratio, or at the window's size (Shader Resolution).
        uint32_t sourceWidth = width, sourceHeight = height;
        const int mode = resolutionMode.load();
        if (mode == 0 || mode == 1) {
            sourceHeight = std::min<uint32_t>(height, mode == 0 ? 240u : 480u);
            sourceWidth = std::max<uint32_t>(1u, static_cast<uint32_t>(std::lround(double(sourceHeight) * width / height)));
        }
        if ((impl->source == nullptr) || (impl->device != device) || (impl->width != width) || (impl->height != height) || (impl->format != format)
            || (impl->sourceWidth != sourceWidth) || (impl->sourceHeight != sourceHeight)) {
            impl->sourceFramebuffer.reset();
            impl->source = device->createTexture(RenderTextureDesc::ColorTarget(sourceWidth, sourceHeight, format));
            impl->sourceWidth = sourceWidth;
            impl->sourceHeight = sourceHeight;
            const RenderTexture *sourceTexture = impl->source.get();
            impl->sourceFramebuffer = device->createFramebuffer(RenderFramebufferDesc(&sourceTexture, 1));
            impl->device = device;
            impl->width = width;
            impl->height = height;
            impl->format = format;
        }

        if (impl->chain == nullptr) {
            struct Busy {
                Busy() { compilingNow = true; }
                ~Busy() { compilingNow = false; }
            } busy;
            libra_shader_preset_t librashaderPreset = nullptr;
            const auto presetU8 = preset.u8string();   // std::string in C++17 (RT64), std::u8string in C++20
            const std::string presetUtf8(presetU8.begin(), presetU8.end());
            libra_error_t error = libra.presetCreate(presetUtf8.c_str(), &librashaderPreset);
            if (error != nullptr) {
                impl->failed = true;
                setStatus("The shader preset couldn't be read: " + errorText(error));
                return false;
            }
            {
                // its parameters, and the player's values from the start
                std::vector<Param> params;
                if (libra.getParams != nullptr) {
                    libra_preset_param_list_t list = {};
                    if (libra.getParams(&librashaderPreset, &list) == nullptr) {
                        for (uint64_t i = 0; i < list.length; i++) {
                            const libra_preset_param_t &p = list.parameters[i];
                            params.push_back({ p.name ? p.name : "", p.description ? p.description : "", p.initial,
                                               p.minimum, p.maximum, p.step });
                        }
                        libra.freeParams(list);
                    }
                }
                std::scoped_lock lock(settingsMutex);
                presetParams = std::move(params);
                if (libra.presetSetParam != nullptr) {
                    for (const auto &[name, value] : paramOverrides) {
                        if (libra_error_t e = libra.presetSetParam(&librashaderPreset, name.c_str(), value)) {
                            errorText(e);
                        }
                    }
                }
                paramsDirty = false;
            }

            VulkanDevice *vulkanDevice = static_cast<VulkanDevice *>(device);
            libra_device_vk_t vulkanObjects = {};
            vulkanObjects.physical_device = vulkanDevice->physicalDevice;
            vulkanObjects.instance = vulkanDevice->renderInterface->instance;
            vulkanObjects.device = vulkanDevice->vk;
            vulkanObjects.queue = VK_NULL_HANDLE;
            vulkanObjects.entry = vkGetInstanceProcAddr;   // volk's: the driver plume runs on (also a Turnip one)

            filter_chain_vk_opt_t options = {};
            options.version = LIBRASHADER_CURRENT_VERSION;
            options.frames_in_flight = 3;
            options.use_dynamic_rendering = false;
            options.disable_cache = true;   // its cache would write into the user's data folders

            RenderCommandList *commandList = worker->commandList.get();
            commandList->begin();
            VkCommandBuffer cmd = static_cast<VulkanCommandList *>(commandList)->vk;
            error = libra.chainCreateDeferred(&librashaderPreset, vulkanObjects, cmd, &options, &impl->chain);
            commandList->end();
            worker->execute();
            worker->wait();
            // creating the chain takes the preset over (and sets the handle to null); freeing it again panics
            if (librashaderPreset != nullptr) {
                libra.presetFree(&librashaderPreset);
            }
            if (error != nullptr) {
                impl->chain = nullptr;
                impl->failed = true;
                setStatus("The shader preset couldn't be built: " + errorText(error));
                return false;
            }

            setStatus(std::string());
            fprintf(stderr, "[Shader] preset in use: %s\n", presetUtf8.c_str());
        }

        // values the player changed since: into the built chain (this thread draws with it)
        std::map<std::string, float> changed;
        {
            std::scoped_lock lock(settingsMutex);
            if (paramsDirty) {
                changed = paramOverrides;
                paramsDirty = false;
            }
        }
        if (libra.chainSetParam != nullptr) {
            for (const auto &[name, value] : changed) {
                if (libra_error_t e = libra.chainSetParam(&impl->chain, name.c_str(), value)) {
                    errorText(e);
                }
            }
        }
        return true;
    }

    RenderFramebuffer *ShaderPass::sourceFramebuffer() const {
        return impl->sourceFramebuffer.get();
    }

    RenderTexture *ShaderPass::sourceTexture() const {
        return impl->source.get();
    }

    uint32_t ShaderPass::sourceWidth() const {
        return impl->sourceWidth;
    }

    uint32_t ShaderPass::sourceHeight() const {
        return impl->sourceHeight;
    }

    void ShaderPass::record(RenderCommandList *commandList, RenderTexture *swapChainTexture) {
        VkCommandBuffer cmd = static_cast<VulkanCommandList *>(commandList)->vk;
        VulkanTexture *source = static_cast<VulkanTexture *>(impl->source.get());
        VulkanTexture *target = static_cast<VulkanTexture *>(swapChainTexture);

        libra_image_vk_t input = {};
        input.handle = source->vk;
        input.format = plume::toVk(source->desc.format);
        input.width = impl->sourceWidth;
        input.height = impl->sourceHeight;

        libra_image_vk_t output = {};
        output.handle = target->vk;
        output.format = (impl->outputFormat != VK_FORMAT_UNDEFINED) ? impl->outputFormat : plume::toVk(target->desc.format);
        output.width = impl->width;
        output.height = impl->height;

        libra_viewport_t viewport = { 0.0f, 0.0f, impl->width, impl->height };
        frame_vk_opt_t options = {};
        options.version = LIBRASHADER_CURRENT_VERSION;
        options.frame_direction = 1;
        options.total_subframes = 1;
        options.current_subframe = 1;
        options.frames_per_second = 60.0f;

        libra_error_t error = libra.chainFrame(&impl->chain, cmd, impl->frameCount++, input, output, &viewport, nullptr, &options);

        // librashader bound its own pipelines, layouts and descriptor sets on this command buffer: forget plume's
        // record of what is bound, so the draws after it (the UI) bind theirs again.
        VulkanCommandList *vulkanList = static_cast<VulkanCommandList *>(commandList);
        vulkanList->activeComputePipelineLayout = nullptr;
        vulkanList->activeGraphicsPipelineLayout = nullptr;
        vulkanList->activeRaytracingPipelineLayout = nullptr;
        vulkanList->graphicsPipelineBound = false;
        vulkanList->computePipelineBound = false;
        vulkanList->graphicsDescriptorSetMask = 0;
        if (error != nullptr) {
            impl->failed = true;
            setStatus("The shader failed while drawing: " + errorText(error));
        }
    }

    void ShaderPass::release() {
        if (libra.loaded) {
            impl->freeChain();
        }
        impl->sourceFramebuffer.reset();
        impl->source.reset();
        impl->device = nullptr;
    }
};
