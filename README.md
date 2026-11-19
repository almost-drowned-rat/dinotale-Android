# DinoTale: Yoshi's Story Recompiled

Yoshi's Story (N64, 1997/98), running natively on PC. The game is statically
recompiled with [N64Recomp](https://github.com/N64Recomp/N64Recomp), runs on
[N64ModernRuntime](https://github.com/N64Recomp/N64ModernRuntime) and is
rendered with [RT64](https://github.com/rt64/rt64). For Windows and Linux,
including the Steam Deck.

Unofficial, and not affiliated with the rights holders. **No game files are
included:** you need your own copy of the game.

## Features

- Runs natively on Windows and Linux.
- **Widescreen:** play in 4:3, stretched, or true widescreen (Expand) that
  shows more of each level, up to 16:9.
- HUD at the edges of the window in widescreen, or kept in its original place.
- RetroArch shaders (librashader): CRT, scanlines, LCD, the xBR, xBRZ and HQ2x upscalers, or any slang preset of
  your own.
- **Android** (this fork's `android` branch): touch buttons, full screen, Android's file picker for the ROM.
- Keyboard and controller support with remappable controls.
- Saves and sound.

## Download and play

Get the latest build from
[Releases](https://github.com/wootbeer/dinotale/releases):

- **Windows:** unzip it anywhere and run `DinoTale.exe`.
- **Linux / Steam Deck:** extract the `.tar.gz` and run `DinoTale`. On the
  Deck, run it from Desktop Mode, or add it to Steam as a non-Steam game to
  play in Game Mode.

On first launch, pick your copy of the game. It's checked against the hash
below and never modified. Settings (graphics, controls, sound) are in the
in-game menu.

### Portable mode

Settings, saves and mods normally go in `%LOCALAPPDATA%\dinotale` (Windows)
or `~/.config/dinotale` (Linux). Add a blank `portable.txt` next to
`DinoTale.exe` (or `DinoTale`) to keep them in the game's own folder instead,
for example on a USB stick. Copy existing settings and saves over by hand.

### Game files

Yoshi's Story (USA) (En,Ja). Any byte order (`.z64`, `.n64`, `.v64`) works,
and it can be picked inside a `.zip` or `.7z` (extract a `.rar` first).
SHA-1 of the `.z64`: `B13072FEF6C6DF48C07D8822C01E5BC59036F6DA`

## Display options

Settings > Graphics > Aspect Ratio:

- **Original:** the game's 4:3 picture, with black bars at the sides.
- **Stretch:** the 4:3 picture stretched to fill the window.
- **Hybrid** (experimental): backgrounds stretched to the window, sprites kept in shape in
  the middle 4:3 area; the stretched sides are dimmed (Hybrid Dimming sets
  how much, down to not at all).
- **Expand** (experimental): true widescreen. The game shows more of each level. Choose it
  before starting a level. HUD Placement sets whether the HUD moves out to
  the window edges.

### Lossless Scaling frame generation

Settings > Graphics > Frame Generation can use
[Lossless Scaling](https://store.steampowered.com/app/993090/Lossless_Scaling/)'s
frame generation (LSFG 3.1) instead of RT64's own, built in, with no
separate app running. It needs your own copy of Lossless Scaling's
`Lossless.dll`, from its install folder: press **Load Lossless.dll** and pick
it (it's copied into DinoTale's folder; nothing from Lossless Scaling comes
with DinoTale). Then set Frame Generation to **Lossless Scaling** and restart:
it runs on the Vulkan graphics API, which it switches to. Multiplier sets 2x,
3x or 4x the game's frame rate; Performance mode is lighter on the GPU. It
adds about one game frame of input delay. Windows and Linux.

### Shaders

Settings > Graphics > Shader runs a RetroArch slang shader over the final picture, through
[librashader](https://github.com/SnowflakePowered/librashader) (Vulkan graphics API). **CRT**, **Scanlines**,
**LCD**, **xBR**, **xBRZ** and **HQ2x** come with the game (`assets/shaders`, licences in
`assets/shaders/LICENSES.txt`). **Custom** runs your own preset, `shaders/custom.slangp`: put a shader pack (for
example [libretro's slang-shaders](https://github.com/libretro/slang-shaders)) into the `shaders` folder and make
`custom.slangp` point at the preset you want, e.g. a file with the one line

```
#reference "slang-shaders/crt/crt-royale.slangp"
```

The `shaders` folder is next to the game, on Android in `Android/data/io.github.almostdrownedrat.dinotale/files/`
(reachable over USB). The desktop builds need `librashader.so` / `librashader.dll` next to the game; without it the
Shader setting does nothing. They replace the earlier Screen Filter setting.

**On Android** the shaders are chosen in the menu Back opens instead (Settings has no Shader option there): Shader >
Import shader pack (.zip) takes a slang shader pack such as libretro/slang-shaders' "Download ZIP", unpacks it into
`Android/data/io.github.almostdrownedrat.dinotale/files/shaders` and lists its folders and presets to pick from;
a choice is used at once. The presets shipped for the desktop aren't used there - a pack offers far more.

Settings > Graphics > **Shader Resolution** sets the size of the game's picture the shader works on:
**Original** (the N64's 240 lines, as in RetroArch - the look the shaders were made for, and by far the lightest;
crt-royale runs at full speed on an Odin 2 Portal), **2x**, or **Full** (the window's size; heavy shaders get slow).

## Android

The `android` branch of this fork builds an APK for arm64 Android devices with Vulkan (Android 8+), made for
handhelds like the AYN Odin 2 Portal and phones alike. The Android layer comes from the
[DK64: Recompiled Android port](https://github.com/deivid22srk/dk64-recomp-android) by deivid22srk.

- **Load ROM** opens Android's file picker; the app restarts once to finish setting up.
- **Touch buttons** (N64 layout: stick, A, B, Z, L, R, START, C buttons; D-pad hidden by default). Their settings
  are under Touch controls in the menu (Back, or the controller icon at the top centre): Touch buttons Automatic / Always / Off (Automatic hides them while a
  controller is used and a touch brings them back), move and resize, button size, show / hide single buttons,
  opacity, stick sensitivity, reset. Touches off the buttons work as a mouse in the menus.
- Android's **Back** (swipe from the edge to show the system bar and tap back, or the back gesture) opens
  the menu in game — the same in all of this fork's Android ports and fully usable with the controller (D-pad /
  stick, **A** choose, **B** back): **Back to the game**, **Game settings and controller** (the game's own menu,
  where the controller is set up) and **Touch controls**. With the game's menu open, Back closes it.
- Lossless Scaling frame generation isn't available on Android (it needs the Windows `Lossless.dll`).

Build: see `android/` and the steps in BUILDING.md (codegen on a Linux host first, then Gradle with NDK 27).

### AI assistance

The Android port and the shader support were made with AI assistance ([Claude Code](https://claude.com/claude-code)).
Planning, testing on the device and the decision what gets released are by the fork's owner.

## Reporting issues

Found something wrong? Open an
[issue](https://github.com/wootbeer/dinotale/issues) with a screenshot, the
level and roughly where it happened, and which aspect ratio you were using.

## Building from source

See [BUILDING.md](BUILDING.md). What changed in each version is in
[CHANGELOG.md](CHANGELOG.md); [STATUS.md](STATUS.md) is the full development
log.

## Credits

- [N64Recomp and N64ModernRuntime](https://github.com/N64Recomp) by Mr-Wiseguy
  and contributors, which this port is built with
- [RT64](https://github.com/rt64/rt64) by Dario and contributors, the renderer
- [RecompFrontend](https://github.com/N64Recomp/RecompFrontend) by the N64Recomp
  contributors, the launcher and input layer
- The [Yoshi's Story decompilation](https://github.com/decompals/yoshis-story)
  by decompals, whose ROM layout, symbol names and overlay loader research
  this project builds on
- [splat](https://github.com/ethteck/splat), `spimdisasm` and `rabbitizer`,
  used to find the game's functions
- [deivid22srk/dk64-recomp-android](https://github.com/deivid22srk/dk64-recomp-android), the Android layer
- [librashader](https://github.com/SnowflakePowered/librashader) by SnowflakePowered (MPL-2.0 / GPL-3.0), the
  shader runtime, and the shipped presets from [libretro/slang-shaders](https://github.com/libretro/slang-shaders)
  (authors and licences in `assets/shaders/LICENSES.txt`)
- The launcher screen's layout follows [DK64 Recompiled](https://github.com/Rainchus/Donkey-Kong-64-Recompiled)'s
  launcher; its pictures (`assets/launcher`: sky, hills, a hill with a tree, clouds) were drawn for DinoTale
  (GPL-3.0), the logo and options use [Fredoka](https://github.com/hafontia/Fredoka-One) by the Fredoka Project
  Authors (SIL OFL 1.1, `assets/FREDOKA_LICENSE.txt`; a static SemiBold instance of the variable font)

## License

The project's own code is GPL-3.0; see [COPYING](COPYING). The game itself
remains the property of its rights holders.
