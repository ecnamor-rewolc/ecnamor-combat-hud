# Combat HUD

> [!WARNING]
> ### STATUS & DISCLAIMER — READ BEFORE INSTALLING
>
> - **NOT A FINAL RELEASE.** This is a personal mod I'm publishing so other people can use it. I will maintain it for some time, but please do not expect long-term support, bug fixes, or future versions.
> - **TESTED ONLY ON STARSECTOR `0.98a-RC8`.** It may work on other versions, but I haven't verified.
> - **ANYONE IS WELCOME TO FORK, MODIFY, OR REPUBLISH.** This project is public domain ([Unlicense](https://unlicense.org/)) — no conditions.

A unified, ~~customizable~~ Starsector combat HUD enhancement suite.

## Features
* **EHP Ring & Directional Spikes**: Renders a clean circle around your flagship showing current EHP, with spikes indicating incoming damage direction for shield-only hits.
* **Battle Boundary Edge Bar**: Draws a glowing perimeter bar that fades in as the camera approaches the map borders.
* **Offscreen Flagship Pointer**: A rotating semi-arc indicator at the screen center with a chevron pointer that directs you toward your flagship when it leaves the screen.
* **Thick Weapon Firing Arcs**: Replaces thin default arcs with thicker, customizable indicators that flicker when weapon slots are disabled.
* **Vanilla HUD Autohide & SFX Lowpass**: Temporarily hides vanilla UI panels during ship overload and applies a sound low-pass filter to emphasize the silence.
* **Killfeed Cap**: Caps the maximum lines in the combat feed (TOP LEFT) to prevent UI clutter.

Most features are individually toggleable and tunable via **LunaLib** settings.

## Installation
1. Download the latest release from the Releases page.
2. Extract the archive into your `<Starsector>/mods/` folder or via TriOS Mod Manager.
3. Enable **Combat HUD** in the Starsector launcher.

## Dependencies
This mod requires the following libraries to be enabled:
* **LazyLib** (by LazyWizard) — [GitHub Repository](https://github.com/LazyWizard/lazylib)
* **LunaLib** (by Lukas22041) — [GitHub Repository](https://github.com/Lukas22041/LunaLib)
* **MagicLib** (by MagicLibStarsector) — [GitHub Repository](https://github.com/MagicLibStarsector/MagicLib)
