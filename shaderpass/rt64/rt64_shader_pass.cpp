// RetroArch slang shader presets for RT64's present, see rt64_shader_pass.h.
// SPDX-License-Identifier: GPL-3.0-or-later
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
        };

        Librashader libra;
        std::mutex settingsMutex;
        std::filesystem::path wantedPreset;
        uint64_t wantedGeneration = 0;
        std::string status;

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
        }
    }

    std::string getShaderStatus() {
        std::scoped_lock lock(settingsMutex);
        return status;
    }

    static std::atomic<bool> compilingNow{ false };

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
        uint32_t width = 0;
        uint32_t height = 0;
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

        // The game's picture is drawn into an image of the swap chain's size and format.
        if ((impl->source == nullptr) || (impl->device != device) || (impl->width != width) || (impl->height != height) || (impl->format != format)) {
            impl->sourceFramebuffer.reset();
            impl->source = device->createTexture(RenderTextureDesc::ColorTarget(width, height, format));
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

        return true;
    }

    RenderFramebuffer *ShaderPass::sourceFramebuffer() const {
        return impl->sourceFramebuffer.get();
    }

    RenderTexture *ShaderPass::sourceTexture() const {
        return impl->source.get();
    }

    void ShaderPass::record(RenderCommandList *commandList, RenderTexture *swapChainTexture) {
        VkCommandBuffer cmd = static_cast<VulkanCommandList *>(commandList)->vk;
        VulkanTexture *source = static_cast<VulkanTexture *>(impl->source.get());
        VulkanTexture *target = static_cast<VulkanTexture *>(swapChainTexture);

        libra_image_vk_t input = {};
        input.handle = source->vk;
        input.format = plume::toVk(source->desc.format);
        input.width = impl->width;
        input.height = impl->height;

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
