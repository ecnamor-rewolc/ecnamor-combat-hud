# Combat HUD - Developer Documentation

This document provides a technical overview of the Combat HUD codebase, its architecture, compilation process, and development guidelines.

## Directory Structure
- `Source/`: Contains the mod source code and resources.
  - `src/`: Java source files.
    - `ecnamor/hud/arcs/`: Implementation of custom weapon firing arcs.
    - `ecnamor/hud/boundary/`: Boundary perimeter indicator rendering.
    - `ecnamor/hud/common/`: Common helper classes, configuration managers, and utility modules.
    - `ecnamor/hud/psi/`: Flagship status ring, damage indicators, and offscreen indicators.
  - `data/`: Game data modifications (LunaLib settings schema, mod descriptions).
  - `graphics/`: Textures and UI graphics (e.g., icons, animated GIFs).
- `tools/`: Build and development helper scripts.
- `local.properties`: Local build paths (ignored by Git, see `local.properties.example`).
- `build.ps1`: Development build and deployment script.
- `build-release.ps1`: Release build preparation and packaging script.

## Core Architecture & Rendering Loop

The mod operates primarily within Starsector's combat loop using a `EveryFrameCombatPlugin`. The core logic is split between world-space rendering and screen-space rendering.

### World-Space vs. Screen-Space Layers

1. **World-Space Rendering**
   - Implemented inside the `advance()` loop or standard draw callbacks when the flagship is active, active-screen coordinates are computed, and normal combat rendering is performed.
   - Drawing occurs in actual game coordinate space.
   - Example: The flagship Status Ring (HP/flux circle), Directional Damage Spikes, and Border Perimeter lines.
   - Includes automatic zoom-level adjustment to ensure visual size consistency regardless of the player's camera zoom level.
   
2. **Screen-Space Rendering**
   - Implemented via `renderInUICoords(ViewportAPI viewport)`.
   - Used for overlays that require static screen positioning or screen-percentage-based scaling (e.g., overload noise screens, screen border vignette effects).
   - Coordinates are relative to the viewport resolution, with the origin `(0,0)` at the bottom-left corner of the screen.

### Hit Recording (Damage indicator)
- A custom `DamageTakenModifier` listener is registered on the flagship (and all child modules for modular ships) to intercept incoming damage events.
- Damage events are classified as shield hits or armor/hull hits and processed into directional segments (buckets) based on the relative angle of the damage source.
- Exponential smoothing is applied to the bucket values to create smooth fade-ins and fade-outs of indicators rather than sudden flashes.

---

## Compilation & Build Pipeline

The project compiles with Java 8 target compatibility. 

### Prerequisites
- JDK 8 or later (e.g., JDK 8, 11, 17, or 21+ with compiler flags `-source 8 -target 8`).
- PowerShell.
- Python 3 (for packaging and release cleanup).

### Setup Local Environment
1. Copy `local.properties.example` to `local.properties`.
2. Open `local.properties` and configure the path to your Starsector installation:
   ```properties
   starsector.dir=C:\\path\\to\\Starsector
   ```

### Running the Development Build
The `build.ps1` script:
- Cleans the build output directory (`Source/bin/`).
- Dynamically resolves dependent API libraries (`LazyLib`, `LunaLib`, `MagicLib`) inside your `<Starsector>/mods/` folder.
- Compiles the Java source files in `Source/src/` to `Source/bin/`.
- Packages the compiled classes into `ecnamor_combat_hud.jar`.
- Deploys the mod folder to the `<Starsector>/mods/` directory.

To run:
```powershell
.\build.ps1
```

### Creating a Clean Release Package
The `build-release.ps1` script runs `tools/build_release.py` to:
- Create a duplicate clean working copy (`Source-release/`).
- Strip developer-only debugging tools (e.g., `Inspector.java`) and config flags.
- Compile and package an optimized production-ready `.jar` binary.
- Bundle the metadata, config assets, and binary into a distributable `.zip` archive.
- Deploy a verification copy to the game mods folder.

To run:
```powershell
.\build-release.ps1
```

---

## Technical Details: Mod Integration
- **LunaLib Settings**: Settings are defined in `Source/data/config/LunaSettings.csv`.
- **Icon Assets**: Includes static `.png` fallback icons and animated `.gif` versions. The mod configuration points to the static `.png` for native LunaLib compatibility, while custom loading logic checks for the presence of the `.gif` and loads it when possible.
