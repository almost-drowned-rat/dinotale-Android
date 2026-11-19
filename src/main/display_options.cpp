// DinoTale's own settings in Settings > Graphics, applied by our RT64 patches
// (render/rt64_screen_filter.h):
// - Screen Filter: post-process filters for the final picture (rt64/0017).
// - Hybrid Dimming: how dark Hybrid's stretched sides are (rt64/0015, 0018).
// - Texture Filtering: Sharp samples textures without filtering (rt64/0024).

#include <string>
#include <variant>
#include <vector>

#include "librecomp/config.hpp"
#include "recompui/config.h"
#include "ultramodern/config.hpp"
#include "rt64_screen_filter.h"
#ifdef DT_SHADER_PRESETS
#include <atomic>
#include <filesystem>
#include "rt64_shader_pass.h"
#endif

namespace {
    const std::string screen_filter_option = "screen_filter";
    const std::string hybrid_dimming_option = "hybrid_dimming";
    const std::string texture_filter_option = "texture_filter";
    // Percent the sides are darkened by; the default was chosen by eye.
    constexpr double default_hybrid_dimming = 88.0;

    const std::vector<recomp::config::ConfigOptionEnumOption> screen_filter_options = {
        {RT64::ScreenFilter::None, "None"},
        {RT64::ScreenFilter::CRT, "CRT"},
        {RT64::ScreenFilter::Scanlines, "Scanlines"},
        {RT64::ScreenFilter::LCD, "LCD"},
        {RT64::ScreenFilter::XBR, "xBR", "xBR"},
        {RT64::ScreenFilter::XBRZ, "xBRZ", "xBRZ"},
        {RT64::ScreenFilter::HQ2x, "HQ2x", "HQ2x"},
    };

#ifdef DT_SHADER_PRESETS
    // Settings > Graphics > Shader: RetroArch slang presets through librashader (shaderpass/rt64). They replace the
    // Screen Filter option (lib-patches/rt64/0017 stays applied, its filter stays at None). The shipped presets are
    // in assets/shaders (LICENSES.txt); Custom is the player's own shaders/custom.slangp.
    const std::string shader_option = "shader";
    enum ShaderChoice : uint32_t { ShaderOff, ShaderCRT, ShaderScanlines, ShaderLCD, ShaderXBR, ShaderXBRZ, ShaderHQ2x, ShaderCustom };
    const std::vector<recomp::config::ConfigOptionEnumOption> shader_options = {
        {ShaderOff, "Off"},
        {ShaderCRT, "CRT"},
        {ShaderScanlines, "Scanlines"},
        {ShaderLCD, "LCD"},
        {ShaderXBR, "xBR", "xBR"},
        {ShaderXBRZ, "xBRZ", "xBRZ"},
        {ShaderHQ2x, "HQ2x", "HQ2x"},
        {ShaderCustom, "Custom"},
    };
    const char* const shipped_presets[] = {
        "",
        "assets/shaders/crt/crt-easymode.slangp",
        "assets/shaders/scanlines/scanline-fract.slangp",
        "assets/shaders/handheld/lcd3x.slangp",
        "assets/shaders/edge-smoothing/xbr/xbr-lv2.slangp",
        "assets/shaders/edge-smoothing/xbrz/xbrz-freescale.slangp",
        "assets/shaders/edge-smoothing/hqx/hq2x-halphon.slangp",
    };
#endif

    const std::vector<recomp::config::ConfigOptionEnumOption> texture_filter_options = {
        {0, "Smooth", "Smooth"},
        {1, "Sharp", "Sharp"},
    };
}

// The folder for the player's own shaders (main.cpp: next to the game on desktop, the app's external folder on Android).
std::filesystem::path dinotale_user_shader_dir();

void dinotale_add_display_options(recomp::config::Config &config) {
#ifdef DT_SHADER_PRESETS
    config.add_enum_option(
        shader_option,
        "Shader",
        "A RetroArch shader for the final picture (librashader). "
        "<recomp-color primary>CRT</recomp-color>: a CRT TV look (crt-easymode). "
        "<recomp-color primary>Scanlines</recomp-color>: dark lines between the N64's lines. "
        "<recomp-color primary>LCD</recomp-color>: a handheld LCD grid (lcd3x). "
        "<recomp-color primary>xBR</recomp-color>, <recomp-color primary>xBRZ</recomp-color> and <recomp-color primary>HQ2x</recomp-color>: "
        "pixel-art upscalers that smooth the edges of the game's 240p picture. "
        "<recomp-color primary>Custom</recomp-color>: your own preset, <b>shaders/custom.slangp</b> (see the README).",
        shader_options,
        ShaderOff,
#ifdef __ANDROID__
        true    // on Android the shader is chosen in the menu Back opens (dinotale_shader_* below, TouchControls)
#else
        false
#endif
    );
#else
    config.add_enum_option(
        screen_filter_option,
        "Screen Filter",
        "A filter for the final picture. "
        "<recomp-color primary>CRT</recomp-color>: a flat CRT TV look, with scanlines, glow and a shadow mask. "
        "<recomp-color primary>Scanlines</recomp-color>: dark lines between the N64's lines. "
        "<recomp-color primary>LCD</recomp-color>: a handheld LCD grid. "
        "<recomp-color primary>xBR</recomp-color>, <recomp-color primary>xBRZ</recomp-color> and <recomp-color primary>HQ2x</recomp-color>: "
        "pixel-art upscalers that smooth the edges of the game's 240p picture.",
        screen_filter_options,
        RT64::ScreenFilter::None
    );

#endif

    config.add_enum_option(
        texture_filter_option,
        "Texture Filtering",
        "<recomp-color primary>Smooth</recomp-color> is the game's own filtering. "
        "<recomp-color primary>Sharp</recomp-color> draws the sprites, HUD and backgrounds with sharp pixels; "
        "3D scenery keeps its smoothing.",
        texture_filter_options,
        0
    );

    config.add_percent_number_option(
        hybrid_dimming_option,
        "Hybrid Dimming",
        "How much the stretched sides are darkened with the <recomp-color primary>Hybrid</recomp-color> aspect ratio, "
        "so they fill the window without drawing the eye. <b>0%</b> leaves them as bright as the rest of the picture.",
        default_hybrid_dimming
    );
    config.add_option_disable_dependency(
        hybrid_dimming_option,
        recompui::config::graphics::options::ar_option,
        ultramodern::renderer::AspectRatio::Original,
        ultramodern::renderer::AspectRatio::Manual,
        ultramodern::renderer::AspectRatio::Stretch,
        ultramodern::renderer::AspectRatio::Expand
    );
}

#ifdef DT_SHADER_PRESETS
// The shader choice for other menus (Android: the menu Back opens, through JNI in virtual_pad.cpp). A choice is
// handed over here and applied - and saved - on the next VI, on the thread that reads the option anyway.
static std::atomic<int> pending_shader{ -1 };
static std::atomic<int> current_shader{ 0 };

int dinotale_shader_count() { return static_cast<int>(shader_options.size()); }
const char* dinotale_shader_name(int i) {
    return i >= 0 && i < static_cast<int>(shader_options.size()) ? shader_options[i].name.c_str() : "";
}
int dinotale_shader_get() {
    int p = pending_shader.load();
    return p >= 0 ? p : current_shader.load();
}
void dinotale_shader_set(int i) {
    if (i >= 0 && i < static_cast<int>(shader_options.size())) pending_shader.store(i);
}
#endif

// Called every VI: hands the applied settings to the renderer.
void dinotale_update_display_options() {
    recomp::config::Config &config = recompui::config::get_config(recompui::config::graphics::id);
#ifdef DT_SHADER_PRESETS
    if (int p = pending_shader.exchange(-1); p >= 0) {
        config.set_option_value(shader_option, static_cast<uint32_t>(p));
        config.save_config();
    }
    const auto shader = config.get_option_value(shader_option);
    if (const uint32_t* value = std::get_if<uint32_t>(&shader)) {
        current_shader.store(static_cast<int>(*value));
        std::filesystem::path preset;
        if (*value == ShaderCustom) {
            preset = dinotale_user_shader_dir() / "custom.slangp";
        }
        else if (*value < std::size(shipped_presets) && shipped_presets[*value][0] != '\0') {
            std::error_code ec;
            preset = std::filesystem::absolute(shipped_presets[*value], ec);
        }
        RT64::setShaderPreset(preset);
    }
#else
    const auto filter = config.get_option_value(screen_filter_option);
    if (const uint32_t* value = std::get_if<uint32_t>(&filter)) {
        RT64::setScreenFilter(RT64::ScreenFilter(*value));
    }
#endif

    const auto texture_filter = config.get_option_value(texture_filter_option);
    if (const uint32_t* value = std::get_if<uint32_t>(&texture_filter)) {
        RT64::setSharpTextures(*value == 1);
    }

    const auto dimming = config.get_option_value(hybrid_dimming_option);
    if (const double* value = std::get_if<double>(&dimming)) {
        RT64::setHybridSideBrightness(float(1.0 - *value / 100.0));
    }
}
