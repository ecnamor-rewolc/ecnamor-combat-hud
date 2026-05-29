# ecnamor mods — Development Summary

Two combat utility mods for Starsector 0.98a-RC8. Both are pure utility (no ships, weapons, or factions), registered via `settings.json` plugins.

---

## ecnamor_psi — Player Ship Indicator

**Mod ID:** `ecnamor_psi` | **Version:** 1.2.0  
**Path:** `C:\my\Starsector\mods\ecnamor_psi-1.2.0`

### What it does

Draws a visual HUD ring around the player-controlled ship in combat. Supports ring / pulsing ring / corner brackets / pulsing brackets styles. Optional damage-aware mode: the ring reacts to incoming fire with directional spikes and color shifts.

### Architecture

| Class | Type | Purpose |
|---|---|---|
| `IndicatorCombatPlugin` | `BaseEveryFrameCombatPlugin` | Main ring renderer |
| `DamageMarkerCombatPlugin` | `BaseEveryFrameCombatPlugin` | Damage HUD ring + spikes |
| `PSIDamageListener` | `DamageTakenModifier` | Per-hit event source |

Both plugins registered in `data/config/settings.json` under `"combatPlugins"`.

### Key technical decisions

**Two bucket arrays, not one.**  
Spikes react to all hits (including shield). The ring vertex color reacts only to armor/hull hits. A single filter would break one or the other. The listener passes `shieldHit` flag to `recordHit()`; spikes always accumulate, circle buckets only accumulate when `!shieldHit`.

**Asymmetric smoothing.**  
`SMOOTH_UP_TAU = 0.10f`, `SMOOTH_DOWN_TAU = 0.20f` — fast grow, controlled fade. Avoids the "lingers too long" artifact while keeping visual coherence.

**Hull panic waves = fixed RED, not dynamic color.**  
Attempted dynamic color (matching the shifted base color) but it was visually confusing. Reverted to `RED = (255, 40, 30)`. Overload waves use `OVERLOAD_COLOR = (200, 90, 255)`.

**Overload detection.**  
`ship.getFluxTracker().isOverloaded()` — when true, purple wave pulses spawn at `OVERLOAD_PULSE_HZ = 3.0f` regardless of damage.

**`%` in `mod_info.json` crashes LunaLib.**  
`String.format()` used internally by LunaLib on mod descriptions. `%` followed by any character is interpreted as a format specifier. Use `percent` in prose.

### Constants

```java
N_BUCKETS       = 90
SPIKE_MAX_LEN   = 200f
SPIKE_CONTRAST  = 1.5f   // power curve for visual contrast
SMOOTH_UP_TAU   = 0.10f
SMOOTH_DOWN_TAU = 0.20f
HULL_PULSE_T    = 0.20f  // hull fraction at which panic starts
PULSE_MIN_HZ    = 1.0f
PULSE_MAX_HZ    = 6.0f
WAVE_SPEED      = 45f
WAVE_MAX_AGE    = 1.4f
OVERLOAD_PULSE_HZ = 3.0f
```

### LunaLib settings

Registered via `data/config/LunaSettingsConfig.json`. Icon at `graphics/icons/on/icon.png`.

---

## ecnamor_boundary — Battle Boundary HUD

**Mod ID:** `ecnamor_boundary` | **Version:** 1.0.0  
**Path:** `C:\my\Starsector\mods\ecnamor_boundary-1.0.0`

### What it does

Draws a red boundary line at map edges, diagonal warning stripes outside (and slightly inside) the boundary, and a gold arrow indicator at the screen edge pointing toward the player flagship when it is off-screen.

### Architecture

| Class | Type | Purpose |
|---|---|---|
| `BoundaryCombatPlugin` | `BaseEveryFrameCombatPlugin` | Registers renderer; draws flagship arrow in UI coords |
| `BoundaryRenderer` | `BaseCombatLayeredRenderingPlugin` | Draws boundary line + stripes in world coords |

`BoundaryRenderer` registered via `engine.addLayeredRenderingPlugin(renderer)` in `BoundaryCombatPlugin.init()`.  
Renders at `CombatEngineLayers.BELOW_SHIPS_LAYER` — under all ships, missiles, and projectiles.  
`getRenderRadius()` returns `9.9999999E14f` (always rendered regardless of camera position).

### Key technical decisions

**`BELOW_SHIPS_LAYER` requires `BaseCombatLayeredRenderingPlugin`.**  
`BaseEveryFrameCombatPlugin.renderInWorldCoords()` does not give layer control. Must use the layered plugin API. Pattern sourced from GraphicsLib, Nightcross Armory, Caymon's Ship Pack.

**Stripes both outside and inside boundary.**  
Camera clamps at map edge — stripes purely outside would never be visible when the camera is already at the limit. A narrow INSIDE band (`INSIDE_DEPTH = 300f`) ensures the warning is visible even when the ship is near the edge.

**45° stripe math.**  
Stripe condition: `d ≤ x − y ≤ d + STRIPE_WIDTH`. Horizontal boundary zones use x-offset parallelogram quads; vertical zones use y-offset quads. Per-vertex alpha interpolation handles distance fade via `GL_QUADS`.

**Camera inertia — not implementable.**  
Investigated `settings.json` (only zoom-related keys) and Better Combat Camera source (modifies only `maxCombatZoom`). Inertia/responsiveness is hardcoded in the engine. No public API.

**Flagship off-screen indicator.**  
`renderInUICoords()` in `BoundaryCombatPlugin`. World → screen via `viewport.convertWorldXtoScreenX/Y()`. Arrow is a filled triangle + small ring, clamped to screen edges with margin. `Global.getSettings().getScreenWidthPixels()` returns `float` — must cast to `int`.

### Constants

```java
OUTSIDE_DEPTH  = 600f    // stripe depth outside boundary
INSIDE_DEPTH   = 300f    // stripe depth inside boundary
OUTSIDE_ALPHA  = 0.35f
INSIDE_ALPHA   = 0.15f
STRIPE_WIDTH   = 20f
STRIPE_SPACING = 40f
```

---

## Icons

**Script:** [`C:\my\starsector-modding\scripts\gen_icons.py`](../scripts/gen_icons.py)  
**Size:** 256×256 PNG, RGBA  
**Dependencies:** `pip install Pillow`

```
python scripts/gen_icons.py
```

Saves to both `graphics/icons/on/icon.png` and `icon.png` in each mod folder.

### psi icon

Dark circular background with radial alpha falloff. Five concentric pulse rings fading outward (VANILLA_BLUE `#46C8FF`, alpha 225 → 25). Navigation arrow (wide chevron shape) centered.

### boundary icon

Square dark background with vignette. Large red border frame (14-stroke thick glow). Full 45° diagonal stripes across interior. Same navigation arrow shape as psi, in RED.

---

## Relevant documentation

### API reference

- [Starfarer API Overview](../html-docs/Overview%20(Starfarer%20API)%20(21.05.2026%2015：40：11).html) — all packages and interfaces in `com.fs.starfarer.api.*`

### IDE / project setup

- [IntelliJ IDEA Setup — Wiki](../html-docs/IntelliJ%20IDEA%20Setup%20-%20The%20Starsector%20Wiki%20(21.05.2026%2016：11：41).html) — JDK config, starfarer.api jar, debug/hotswap

### GL11 rendering patterns

- [FieldRenderer.java](../code-examples/FieldRenderer.java) — `BaseCombatLayeredRenderingPlugin`, `GL_TRIANGLE_STRIP`, alpha fade at inner/outer radius, UV scroll. **Direct pattern used for `BoundaryRenderer`.**
- [FleetStatusUIPlugin.java](../code-examples/FleetStatusUIPlugin.java) — `BaseEveryFrameCombatPlugin` HUD overlay, GL state push/pop, `renderInUICoords()` pattern. **Direct pattern used for both plugins.**

### Modding guidelines and pitfalls

- [Modding Guidelines](../html-docs/Modding%20Guidelines%20(21.05.2026%2014：59：02).html) — community rules, best practices
- [StarModGuide.txt](../guides/StarModGuide.txt) — static variable memory leak deep-dive, `@Transient` fields, serialization, VisualVM heap dump, OP budgets, fleet roles
- [Unable to Load Certain Saves](../html-docs/Unable%20to%20load%20certain%20saves%20(21.05.2026%2015：16：32).html) — serialization pitfalls, transient fields, class version mismatches

### Mod naming

- [Already Used Names.pdf](../tools/Copy%20of%20Already%20used%20names.pdf) — claimed mod IDs/prefixes to avoid conflicts

---

## File map

```
ecnamor_psi-1.2.0/
  mod_info.json
  data/config/settings.json          ← plugin registration
  data/config/LunaSettingsConfig.json
  src/ecnamor/psi/
    IndicatorCombatPlugin.java        ← ring styles, LunaLib settings read
    DamageMarkerCombatPlugin.java     ← damage HUD, spikes, waves
    PSIDamageListener.java            ← DamageTakenModifier
  graphics/icons/on/icon.png
  icon.png
  jars/ecnamor_psi.jar

ecnamor_boundary-1.0.0/
  mod_info.json
  data/config/settings.json          ← plugin registration
  data/config/LunaSettingsConfig.json
  src/ecnamor/boundary/
    BoundaryCombatPlugin.java         ← registers renderer, flagship arrow
    BoundaryRenderer.java             ← world-space GL11 boundary + stripes
  graphics/icons/on/icon.png
  icon.png
  jars/ecnamor_boundary.jar

starsector-modding/scripts/
  gen_icons.py                        ← icon generator (256×256, Pillow)
```
