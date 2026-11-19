// DinoTale's launcher screen: a picture-book scene (assets/launcher: sky and stitched hills, a hill with a
// long-necked dinosaur, felt clouds - drawn for DinoTale), the logo at the top and the game's options on the right,
// laid out like DK64 Recompiled's launcher. A short intro (logo pops in, the hill rises, the options slide in) is
// skipped by any key, button or click.
// SPDX-License-Identifier: GPL-3.0-or-later
#include "dinotale_launcher.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <string>
#include <vector>

#include "RmlUi/Core/PropertyDictionary.h"
#include "RmlUi/Core/StyleSheetSpecification.h"
#include "elements/ui_svg.h"
#include "base/ui_game_option.h"

namespace {

// recompui keeps raw RmlUi properties protected (Style::set_property, virtual; Element overrides it); this reaches
// it legally - a member pointer taken inside a class derived from Style - for the one thing recompui has no setter
// for: the text outline. Called on an Element, the virtual call lands in Element's override.
struct PropertyAccess : recompui::Style {
    using Setter = void (recompui::Style::*)(Rml::PropertyId, const Rml::Property &);
    static Setter setter() { return &PropertyAccess::set_property; }
};

void set_rml(recompui::Element *e, const char *name, const char *value) {
    Rml::PropertyDictionary d;
    if (!Rml::StyleSheetSpecification::ParsePropertyDeclaration(d, name, value)) return;
    auto set = PropertyAccess::setter();
    for (const auto &[id, prop] : d.GetProperties()) (static_cast<recompui::Style *>(e)->*set)(id, prop);
}

constexpr const char *kOutlineFx = "outline(5dp #2a6f8f)";
constexpr const char *kFont = "Fredoka SemiBold";

struct Key { float t, x, y; };

// Keyframes: (time, x, y); between two, smootherstep; after the last, hold (or loop from `loop_from`).
float smootherstep(float t) { return t * t * t * (t * (t * 6.0f - 15.0f) + 10.0f); }

void sample(const std::vector<Key> &k, float time, int loop_from, float &x, float &y) {
    if (k.empty()) return;
    if (loop_from >= 0 && time > k.back().t) {
        float span = k.back().t - k[loop_from].t;
        if (span > 0) time = k[loop_from].t + std::fmod(time - k[loop_from].t, span);
    }
    if (time <= k.front().t) { x = k.front().x; y = k.front().y; return; }
    for (size_t i = 0; i + 1 < k.size(); i++) {
        if (time < k[i + 1].t) {
            float s = smootherstep((time - k[i].t) / (k[i + 1].t - k[i].t));
            x = k[i].x + (k[i + 1].x - k[i].x) * s;
            y = k[i].y + (k[i + 1].y - k[i].y) * s;
            return;
        }
    }
    x = k.back().x; y = k.back().y;
}

// A picture placed relative to the screen centre (x, y in dp), scaled about its centre.
struct Sprite {
    recompui::Element *el = nullptr;
    float w = 0, h = 0;
    std::vector<Key> pos, scale;      // scale uses x for both axes
    int pos_loop = -1;
    bool anchor_bottom = false;       // y is measured from the bottom edge instead of the centre

    void update(float time, float bg_w, float bg_h) {
        float x = 0, y = 0, s = 1, unused = 0;
        sample(pos, time, pos_loop, x, y);
        sample(scale, time, -1, s, unused);
        float cy = anchor_bottom ? bg_h - h / 2.0f : bg_h / 2.0f;
        el->set_translate_2D(bg_w / 2.0f - w / 2.0f + x, cy - h / 2.0f + y);
        el->set_scale_2D(s, s);
    }
};

Sprite make_svg(recompui::ContextId ctx, recompui::Element *parent, const std::string &path, float w, float h) {
    Sprite s;
    s.el = ctx.create_element<recompui::Svg>(parent, path);
    s.el->set_position(recompui::Position::Absolute);
    s.el->set_width(w, recompui::Unit::Dp);
    s.el->set_height(h, recompui::Unit::Dp);
    s.w = w; s.h = h;
    return s;
}

constexpr float kMenuIn = 1.1f, kMenuInEnd = 1.7f, kSkipTo = 4.0f;
constexpr float kMenuRightStart = -40.0f, kMenuRightEnd = 24.0f;
constexpr float kMenuTopOffset = 96.0f;

struct State {
    recompui::Element *bg = nullptr;
    Sprite hill, logo;
    std::array<Sprite, 3> clouds;
    float seconds = 0, menu_phase = -1;
    std::chrono::steady_clock::time_point last;
    bool started = false, enabled = false, skipped = false;
    std::atomic<bool> skip_next{ false };
} st;

int skip_watch(void *, SDL_Event *e) {
    bool skip = false;
    switch (e->type) {
    case SDL_KEYDOWN: case SDL_CONTROLLERBUTTONDOWN: case SDL_MOUSEBUTTONDOWN: case SDL_FINGERDOWN: skip = true; break;
    default: break;
    }
    if (!st.skipped && skip) { st.skipped = true; st.skip_next = true; return 0; }
    return 1;
}

} // namespace

void dinotale::launcher_init(recompui::LauncherMenu *menu, recompui::GameOptionsMenu *options) {
    auto ctx = recompui::get_current_context();

    // The options: right-aligned, big rounded letters, white with a blue outline, orange when focused.
    options->set_width(30, recompui::Unit::Percent);
    for (auto option : options->get_options()) {
        option->set_justify_content(recompui::JustifyContent::FlexEnd);
        option->set_border_radius(0);
        option->set_padding(20.0f);
        option->set_font_family(kFont);
        option->set_color(recompui::Color{ 0xff, 0xff, 0xff, 0xff });
        set_rml(option, "font-effect", kOutlineFx);
        for (auto style : { &option->hover_style, &option->focus_style }) {
            style->set_background_color(recompui::theme::color::Transparent);
            style->set_color(recompui::Color{ 0xff, 0x8a, 0x3d, 0xff });
        }
        auto label = option->get_label();
        label->set_font_size(56.0f);
        label->set_letter_spacing(2.0f);
        option->set_enabled(false);
        option->set_opacity(0.0f);
    }

    recompui::Element *menu_container = menu->get_menu_container();
    menu_container->set_width(1440);
    menu_container->unset_left();
    menu_container->set_top(kMenuTopOffset);
    menu_container->set_bottom(-kMenuTopOffset);
    menu_container->set_right(50, recompui::Unit::Percent);
    menu_container->set_translate_2D(50.0f, 0.0f, recompui::Unit::Percent);
    options->unset_left();
    options->set_bottom(50.0f, recompui::Unit::Percent);
    options->set_translate_2D(0.0f, 50.0f, recompui::Unit::Percent);
    options->set_right(kMenuRightStart);
    menu->remove_default_title();

    recompui::Element *bgc = menu->get_background_container();
    bgc->set_background_color({ 0x8f, 0xd6, 0xff, 0xff });

    // Back to front: sky and hills, clouds, the dinosaur's hill, the logo.
    st.bg = ctx.create_element<recompui::Svg>(bgc, "launcher/bg.svg");
    st.bg->set_position(recompui::Position::Absolute);
    st.bg->set_top(0);
    st.bg->set_left(0);
    st.bg->set_width(100, recompui::Unit::Percent);
    st.bg->set_height(100, recompui::Unit::Percent);

    const std::array<std::array<float, 4>, 3> cloud_at = { {   // x, y, scale, bob period
        { -560.0f, -400.0f, 0.9f, 7.0f }, { 420.0f, -250.0f, 0.75f, 6.0f }, { -60.0f, -170.0f, 0.5f, 8.0f } } };
    for (size_t i = 0; i < st.clouds.size(); i++) {
        auto &c = st.clouds[i];
        c = make_svg(ctx, bgc, "launcher/cloud.svg", 460.0f, 180.0f);
        float x = cloud_at[i][0], y = cloud_at[i][1], p = cloud_at[i][3], d = 0.3f + 0.25f * i;
        c.pos = { { 0.0f, x - 40.0f, y }, { d, x - 40.0f, y }, { d + p / 2.0f, x + 40.0f, y - 8.0f }, { d + p, x - 40.0f, y } };
        c.pos_loop = 1;
        c.scale = { { 0.0f, 0.0f, 0 }, { d, 0.0f, 0 }, { d + 0.35f, cloud_at[i][2], 0 } };
    }

    st.hill = make_svg(ctx, bgc, "launcher/island.svg", 750.0f, 600.0f);
    st.hill.anchor_bottom = true;
    st.hill.pos = { { 0.0f, -560.0f, 600.0f }, { 0.2f, -560.0f, 600.0f }, { 0.9f, -560.0f, 45.0f } };

    // The logo: a label in a box the size of the screen's top, popped in by scale.
    auto logo = ctx.create_element<recompui::Label>(bgc, "DinoTale", recompui::LabelStyle::Large);
    logo->set_position(recompui::Position::Absolute);
    logo->set_font_family(kFont);
    logo->set_font_size(150.0f);
    logo->set_color(recompui::Color{ 0xff, 0xff, 0xff, 0xff });
    set_rml(logo, "font-effect", "outline(10dp #2a6f8f)");
    logo->set_text_align(recompui::TextAlign::Center);
    st.logo.el = logo;
    st.logo.w = 1000.0f;
    st.logo.h = 200.0f;
    logo->set_width(st.logo.w, recompui::Unit::Dp);
    logo->set_height(st.logo.h, recompui::Unit::Dp);
    st.logo.pos = { { 0.0f, 0.0f, -360.0f } };
    st.logo.scale = { { 0.0f, 0.0f, 0 }, { 0.25f, 0.0f, 0 }, { 0.55f, 1.08f, 0 }, { 0.7f, 1.0f, 0 } };

    SDL_AddEventWatch(&skip_watch, nullptr);
}

void dinotale::launcher_update(recompui::LauncherMenu *menu) {
    auto now = std::chrono::steady_clock::now();
    float dt = st.started ? std::chrono::duration<float>(now - st.last).count() : 0.0f;
    if (st.skip_next.exchange(false)) dt = std::max(kSkipTo - st.seconds, 0.0f);
    st.seconds += dt;
    st.last = now;
    st.started = true;

    recompui::Element *bgc = menu->get_background_container();
    float r = bgc->get_dp_to_pixel_ratio();
    float bw = bgc->get_client_width() / r, bh = bgc->get_client_height() / r;
    for (auto &c : st.clouds) c.update(st.seconds, bw, bh);
    st.hill.update(st.seconds, bw, bh);
    st.logo.update(st.seconds, bw, bh);

    auto options = menu->get_game_options_menu();
    float phase = std::clamp((st.seconds - kMenuIn) / (kMenuInEnd - kMenuIn), 0.0f, 1.0f);
    if (phase != st.menu_phase && options != nullptr) {
        float s = smootherstep(phase);
        for (auto option : options->get_options()) option->set_opacity(s);
        options->set_right(kMenuRightStart + (kMenuRightEnd - kMenuRightStart) * s);
        st.menu_phase = phase;
    }
    if (!st.enabled && st.seconds >= kMenuInEnd && options != nullptr) {
        SDL_DelEventWatch(&skip_watch, nullptr);
        for (auto option : options->get_options()) { option->set_enabled(true); option->set_opacity(1.0f); }
        st.enabled = true;
    }
}
