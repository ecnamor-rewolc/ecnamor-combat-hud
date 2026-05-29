package ecnamor.hud.psi;

/*
 * ──────────────────────────────────────────────────────────────────────────────
 *  Damage-reactive ehp ring + directional shield damage spikes around the player ship.
 *
 *  Renders two passes on the health ring (solid circle + ripple wobble), 5-spike
 *  staircase clusters per attacking ship, inward contracting waves on low hull,
 *  and a CRT-style noise overlay (with audio low-pass) during overload.
 *
 *  Reflection helpers in ecnamor.hud.common.* hide vanilla HUD on overload,
 *  cap the killfeed line count.
 *
 *  -- TUNING (search the marked sections below) --
 *    Timing            : DECAY_HALF, SMOOTH_UP_TAU, SMOOTH_DOWN_TAU
 *    Sensitivity       : SPIKE_EHP_FRAC, CIRCLE_EHP_FRAC
 *    Spike visuals     : SPIKE_SIDE_DEG, SPIKE_MAX_LEN, SPIKE_CONTRAST
 *    Ripple in ring    : RIPPLE_AMP, RIPPLE_FREQ, RIPPLE_HZ_MIN/MAX
 *    Hull-low pulse    : HULL_PULSE_T, PULSE_MIN_HZ, PULSE_MAX_HZ
 *    Inward waves      : WAVE_SPEED, WAVE_MAX_AGE, MAX_WAVES, WAVE_SPAWN_AT
 *    Overload overlay  : NOISE_FADE_IN/OUT, OVERLOAD_LOWPASS
 *    Status bar gap    : STATUS_SKIP_START / STATUS_SKIP_END / STATUS_SKIP_FADE
 *    Edge fade         : EDGE_MARGIN, EDGE_WIDTH
 *    Death overlay     : DEATH_FADE_IN, DEATH_FADE_OUT
 *
 *  Runtime toggles are exposed in data/config/LunaSettings.csv under tab
 *  "Damage Indicator".
 * ──────────────────────────────────────────────────────────────────────────────
 */

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import ecnamor.hud.common.HudControl;
import ecnamor.hud.common.KillFeedCap;

public class DamageMarkerCombatPlugin extends BaseEveryFrameCombatPlugin {

    private static final String MOD_ID = "ecnamor_combat_hud";

    // ── Timing ────────────────────────────────────────────────────────────────
    private static final int   N_BUCKETS       = 90;
    private static final float DECAY_HALF      = 1.4f;
    private static final float SMOOTH_UP_TAU   = 0.25f;
    private static final float SMOOTH_DOWN_TAU = 0.35f;

    // ── EHP reference fractions ───────────────────────────────────────────────
    private static final float SPIKE_EHP_FRAC  = 0.06f;
    private static final float CIRCLE_EHP_FRAC = 0.05f;
    private static final float SHIELD_GAP      = 22f;

    // ── Colors ────────────────────────────────────────────────────────────────
    private static final Color YELLOW = new Color(255, 200,  30);
    private static final Color ORANGE = new Color(255, 120,  20);
    private static final Color RED    = new Color(255,  40,  30);

    // ── Spikes ────────────────────────────────────────────────────────────────
    /** Degrees between each of the 5 staircase spikes per target. */
    private static final float SPIKE_SIDE_DEG = 1.0f;   // tight 5-spike cluster
    private static final float SPIKE_GAP      = 42f;   // outside ripple layer
    private static final float SPIKE_MAX_LEN  = 234f;
    private static final float SPIKE_MIN_FRAC = 0.003f;
    private static final float SPIKE_CONTRAST = 1.3f;

    // ── Flux sensitivity ──────────────────────────────────────────────────────
    private static final float FLUX_SPIKE_T         = 0.65f;
    private static final float FLUX_SPIKE_MIN_HZ    = 1.0f;
    private static final float FLUX_SPIKE_MAX_HZ    = 5.0f;
    private static final float FLUX_SENSITIVITY_MAX = 0.60f;

    // ── Hull-low pulse ────────────────────────────────────────────────────────
    private static final float HULL_PULSE_T = 0.20f;
    private static final float PULSE_MIN_HZ = 1.0f;
    private static final float PULSE_MAX_HZ = 6.0f;
    private static final float SUPPRESS_CD  = 8.0f;

    // ── Overload blackout ─────────────────────────────────────────────────────
    private static final float NOISE_FADE_IN  = 2.5f;   // alpha per second
    private static final float NOISE_FADE_OUT = 1.5f;
    /** Max low-pass filter intensity (0=no filter, 1=full muffle). Scaled by overloadNoiseAlpha. */
    private static final float OVERLOAD_LOWPASS = 0.95f;

    // ── Screen edge fade (all 4 edges) ────────────────────────────────────────
    /** Px from each screen edge where fade begins. */
    private static final float EDGE_MARGIN = 55f;
    /** Px width of the fade gradient. */
    private static final float EDGE_WIDTH  = 45f;

    // ── Vanilla HUD status bar — angular arc gap in health circle ────────────
    /** Start angle of the arc gap in degrees (0° = right, 90° = up). */
    private static final float STATUS_SKIP_START = 20f;
    /** End angle of the arc gap in degrees. */
    private static final float STATUS_SKIP_END   = 45f;
    /** Fade zone width in degrees at each edge of the gap. */
    private static final float STATUS_SKIP_FADE  =  8f;

    // ── Death noise effect ─────────────────────────────────────────────────
    /** Seconds to fade in the death overlay. */
    private static final float DEATH_FADE_IN  = 0.9f;
    /** Seconds to fade out when new ship is taken. */
    private static final float DEATH_FADE_OUT = 0.7f;

    // ── Preset names ──────────────────────────────────────────────────────────
    private static final String PRESET_GREEN = "Vanilla Green";
    private static final String PRESET_BLUE  = "Vanilla Blue";

    // ── Excluded hulls ────────────────────────────────────────────────────────
    private static final String[] EXCLUDED_HULLS = { "shuttlePod", "shuttlepod", "shuttle_pod", "command_shuttle" };

    // ── Runtime state ─────────────────────────────────────────────────────────
    private CombatEngineAPI engine;
    private ViewportAPI     viewport;
    private float           time            = 0f;
    private float           pulsePhase      = 0f;
    private float           lastSin         = 0f;
    private float           spikeFluxPhase  = 0f;
    private float           suppressTimer   = 0f;
    private boolean         prevKeyO        = false;

    // Overload blackout
    private boolean wasOverloaded       = false;
    private float   overloadStartTime   = 0f;
    private float   overloadNoiseAlpha  = 0f;
    private boolean overloadNoiseEnabled= false;

    // Death effect
    private float   deathNoiseAlpha     = 0f;
    private boolean deathEffectActive   = false;
    private boolean trackedWasAlive     = false;
    private boolean prevShipRetreating  = false;

    // Render-state (survives pause)
    private float   renderCircleAlpha    = 0f;
    private float   renderEffSpikeRef    = 1f;
    private float   renderSpikePulseMult = 1f;
    private float   renderBaseR          = 100f;
    private float   renderSpikeBaseR     = 100f;
    private float   renderSpikeFadeMult  = 1f;
    private float   renderHullFrac       = 1f;
    private boolean renderIsOverloaded   = false;
    private boolean renderShieldOn       = false;
    private boolean hugeShieldMode       = false;

    // Settings
    private boolean enabled        = true;
    private Color   baseColor      = IndicatorCombatPlugin.VANILLA_GREEN;
    private float   alpha          = 0.75f;
    private float   lineWidth      = 3.0f;
    private float   sizeMult       = 1.0f;
    private boolean showWithoutHUD = true;
    private boolean directionEnabled = true;
    /** -1 = no cap (vanilla), 0 = fully hidden, N>0 = trim to N most recent. */
    private int     killfeedMax    = -1;
    private boolean hideFfIndicators = true;

    // Smooth radius transition
    private float   lastBeatSin     = 0f;
    private float   currentBaseR    = 100f;

    // Buckets
    private final float[] rawBuckets          = new float[N_BUCKETS];
    private final float[] smoothBuckets       = new float[N_BUCKETS];
    private final float[] rawCircleBuckets    = new float[N_BUCKETS];
    private final float[] smoothCircleBuckets = new float[N_BUCKETS];
    private float smoothCircleMax = 1f;
    private float spikeRef        = 1f;
    private float circleRef       = 1f;

    private final ArrayList<HitRecord> hitRecords = new ArrayList<HitRecord>();

    private ShipAPI           tracked;
    private PSIDamageListener listener;
    private final HudControl hudControl = new HudControl();
    private final KillFeedCap killFeedCap = new KillFeedCap();


    // ── Data ──────────────────────────────────────────────────────────────────

    /** Maximum number of fighter-missile hit records kept simultaneously. */
    private static final int FIGHTER_HIT_CAP = 24;

    private static final int SG_64 = 64;
    private static final float[] COS_64 = new float[64];
    private static final float[] SIN_64 = new float[64];
    static {
        for (int i = 0; i < 64; i++) {
            double rad = 2.0 * Math.PI * i / 64;
            COS_64[i] = (float) Math.cos(rad);
            SIN_64[i] = (float) Math.sin(rad);
        }
    }

    private static class HitRecord {
        final ShipAPI  source;
        final Vector2f fallback;
        float          weight;
        final boolean  shieldHit;
        /** True for missile hits originating from a fighter (angle-only, no source tracking). */
        final boolean  fighterMissile;
        HitRecord(ShipAPI src, Vector2f fb, float dmg, boolean sh, boolean fm) {
            source = src; fallback = new Vector2f(fb); weight = dmg;
            shieldHit = sh; fighterMissile = fm;
        }
        /** Convenience ctor for regular (non-fighter) hits. */
        HitRecord(ShipAPI src, Vector2f fb, float dmg, boolean sh) {
            this(src, fb, dmg, sh, false);
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        time = 0f; pulsePhase = 0f; lastSin = 0f; lastBeatSin = 0f;
        spikeFluxPhase = 0f;
        suppressTimer = 0f; prevKeyO = false;
        wasOverloaded = false; overloadStartTime = 0f; overloadNoiseAlpha = 0f;
        renderCircleAlpha = 0f; renderEffSpikeRef = 1f;
        renderSpikePulseMult = 1f; renderBaseR = 100f;
        renderHullFrac = 1f; renderIsOverloaded = false;
        Arrays.fill(rawBuckets, 0f); Arrays.fill(smoothBuckets, 0f);
        Arrays.fill(rawCircleBuckets, 0f); Arrays.fill(smoothCircleBuckets, 0f);
        smoothCircleMax = 1f; spikeRef = 1f; circleRef = 1f;
        hitRecords.clear();
        tracked = null; listener = null;
        loadSettings();
    }

    private void loadSettings() {
        if (!Global.getSettings().getModManager().isModEnabled("lunalib")) return;
        try {
            Boolean b; Double d; Color c;
            b = lunalib.lunaSettings.LunaSettings.getBoolean(MOD_ID, "psi_dmg_enabled");
            if (b != null) enabled = b;
            String preset = lunalib.lunaSettings.LunaSettings.getString(MOD_ID, "psi_color_preset");
            if (PRESET_GREEN.equals(preset))     baseColor = IndicatorCombatPlugin.VANILLA_GREEN;
            else if (PRESET_BLUE.equals(preset)) baseColor = IndicatorCombatPlugin.VANILLA_BLUE;
            else {
                c = lunalib.lunaSettings.LunaSettings.getColor(MOD_ID, "psi_color");
                if (c != null) baseColor = c;
            }
            d = lunalib.lunaSettings.LunaSettings.getDouble(MOD_ID, "psi_alpha");
            if (d != null) alpha = MathUtils.clamp(d.floatValue(), 0f, 1f);
            d = lunalib.lunaSettings.LunaSettings.getDouble(MOD_ID, "psi_size_mult");
            if (d != null) sizeMult = MathUtils.clamp(d.floatValue(), 0.1f, 4f);
            d = lunalib.lunaSettings.LunaSettings.getDouble(MOD_ID, "psi_line_width");
            if (d != null) lineWidth = MathUtils.clamp(d.floatValue(), 0.5f, 16f);
            b = lunalib.lunaSettings.LunaSettings.getBoolean(MOD_ID, "psi_show_without_hud");
            if (b != null) showWithoutHUD = b;
            b = lunalib.lunaSettings.LunaSettings.getBoolean(MOD_ID, "psi_direction_enabled");
            if (b != null) directionEnabled = b;
            b = lunalib.lunaSettings.LunaSettings.getBoolean(MOD_ID, "psi_overload_noise");
            if (b != null) overloadNoiseEnabled = b;
            String kf = lunalib.lunaSettings.LunaSettings.getString(MOD_ID, "psi_killfeed_mode");
            if ("Hidden".equals(kf))            killfeedMax = 0;
            else if ("Cap 10 lines".equals(kf)) killfeedMax = 10;
            else                                killfeedMax = -1;
            b = lunalib.lunaSettings.LunaSettings.getBoolean(MOD_ID, "psi_hide_ff_indicators");
            if (b != null) hideFfIndicators = b;

        } catch (Exception ignored) {}
    }

    // ── Main loop ─────────────────────────────────────────────────────────────

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null) return;
        if (hideFfIndicators) {
            ecnamor.hud.common.Inspector.inspect(engine);
        }
        if (engine.isUIShowingDialog()) return;
        if (!enabled) return;

        boolean paused = engine.isPaused();

        // ── UNCONDITIONAL ALPHA UPDATES (run even when ship dead / in shuttle) ────
        if (!paused) {
            time += amount;

            ShipAPI anyShip = engine.getPlayerShip();
            boolean validShip = anyShip != null && anyShip.isAlive()
                    && !anyShip.isHulk() && !isExcludedHull(anyShip);
            boolean inShuttle = anyShip != null && anyShip.isAlive()
                    && !anyShip.isHulk() && isExcludedHull(anyShip);

            // ─ Death detection ────────────────────────────────────────────────────────────
            // Track retreat status while ship is still valid (isRetreating() only works while on battlefield)
            if (validShip) prevShipRetreating = anyShip.isRetreating();
            if (trackedWasAlive && !validShip && !inShuttle && !prevShipRetreating) {
                // Ship actually destroyed (not retreated, not voluntary switch to shuttle)
                deathEffectActive = true;
                // If was overloaded, take over smoothly from overload alpha
                if (overloadNoiseAlpha > 0.01f) deathNoiseAlpha = overloadNoiseAlpha;
                // Restore vanilla HUD if we forced it hidden during overload
                if (wasOverloaded && overloadNoiseEnabled) {
                    hudControl.onOverloadEdge(engine, false);
                    wasOverloaded = false;
                }
            }
            if (!validShip && !inShuttle) prevShipRetreating = false; // reset when no ship at all
            if (validShip && deathEffectActive) {
                deathEffectActive = false;  // new valid ship taken
            }
            trackedWasAlive = validShip;

            // ─ Death noise alpha ─────────────────────────────────────────────────────────
            // Activate only when the ship was actually destroyed, NOT on voluntary switch.
            // deathEffectActive stays true while in shuttle after death.
            if (deathEffectActive) {
                deathNoiseAlpha = Math.min(1f, deathNoiseAlpha + amount * DEATH_FADE_IN);
            } else {
                deathNoiseAlpha = Math.max(0f, deathNoiseAlpha - amount * DEATH_FADE_OUT);
            }

            // ─ Overload noise alpha ─────────────────────────────────────────────────────
            if (overloadNoiseEnabled) {
                boolean activeOverload = validShip
                        && anyShip.getFluxTracker() != null
                        && anyShip.getFluxTracker().isOverloaded();
                if (activeOverload) {
                    overloadNoiseAlpha = Math.min(1f, overloadNoiseAlpha + amount * NOISE_FADE_IN);
                } else {
                    overloadNoiseAlpha = Math.max(0f, overloadNoiseAlpha - amount * NOISE_FADE_OUT);
                }
            } else {
                overloadNoiseAlpha = 0f;
            }

            // ─ Killfeed mode — trim or empty vanilla message panel ───────────────────
            //   killfeedMax < 0 → vanilla (no change), 0 → fully hidden, N → cap to N.
            if (killfeedMax >= 0) killFeedCap.cap(engine, killfeedMax);

        }

        float filterIntensity = Math.max(overloadNoiseAlpha, deathNoiseAlpha) * OVERLOAD_LOWPASS;
        if (filterIntensity > 0.01f) {
            try {
                Global.getSoundPlayer().applyLowPassFilter(filterIntensity, 0f);
            } catch (Throwable ignored) {}
        }

        // ── FROM HERE: only when a valid, non-excluded, live ship exists ────────────
        ShipAPI ship = engine.getPlayerShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) return;
        if (isExcludedHull(ship)) return;
        if (!showWithoutHUD && !engine.isUIShowingHUD()) return;
        if (engine.getCombatUI() != null && engine.getCombatUI().isShowingCommandUI()) return;

        // ── STATE UPDATE ──────────────────────────────────────────────────────
        if (!paused) {
            boolean currKeyO = Keyboard.isKeyDown(Keyboard.KEY_O);
            if (currKeyO && !prevKeyO) suppressTimer = SUPPRESS_CD;
            prevKeyO = currKeyO;
            if (suppressTimer > 0f) suppressTimer -= amount;

            // Track ship change
            if (ship != tracked) {
                detachListeners();
                hitRecords.clear();
                Arrays.fill(rawBuckets, 0f); Arrays.fill(smoothBuckets, 0f);
                Arrays.fill(rawCircleBuckets, 0f); Arrays.fill(smoothCircleBuckets, 0f);
                smoothCircleMax = 1f;
                pulsePhase = 0f; lastSin = 0f;
                spikeFluxPhase = 0f;
                spikeRef  = calcSpikeRef(ship);
                circleRef = calcCircleRef(ship);
                tracked   = ship;
                attachListeners(ship);
            }

            // Decay hit records
            float decay = (float) Math.pow(0.5, amount / DECAY_HALF);
            Iterator<HitRecord> it = hitRecords.iterator();
            while (it.hasNext()) {
                HitRecord r = it.next();
                r.weight *= decay;
                if (r.weight < 0.08f) it.remove();
            }

            Arrays.fill(rawBuckets, 0f);
            Arrays.fill(rawCircleBuckets, 0f);
            Vector2f visualCenter = getVisualCenter(tracked);
            ShieldAPI bShield = tracked.getShield();
            Vector2f shieldCenter = (bShield != null) ? bShield.getLocation() : visualCenter;
            float newCRaw = 0f;
            for (HitRecord r : hitRecords) {
                Vector2f from = (r.source != null && r.source.isAlive())
                        ? r.source.getLocation() : r.fallback;
                Vector2f center = r.shieldHit ? shieldCenter : visualCenter;
                float dx = from.x - center.x, dy = from.y - center.y;
                if (dx == 0f && dy == 0f) continue;
                float deg = (float) Math.toDegrees(Math.atan2(dy, dx));
                if (deg < 0f) deg += 360f;
                int idx = clampIdx((int) (deg / (360f / N_BUCKETS)));
                if (r.shieldHit) {
                    rawBuckets[idx] += r.weight;
                } else {
                    rawCircleBuckets[idx] += r.weight;
                    if (rawCircleBuckets[idx] > newCRaw) newCRaw = rawCircleBuckets[idx];
                }
            }

            // Smooth buckets
            float upRate   = 1f - (float) Math.exp(-amount / SMOOTH_UP_TAU);
            float downRate = 1f - (float) Math.exp(-amount / SMOOTH_DOWN_TAU);
            for (int i = 0; i < N_BUCKETS; i++) {
                float diff = rawBuckets[i] - smoothBuckets[i];
                smoothBuckets[i] += diff * (diff > 0f ? upRate : downRate);
                if (smoothBuckets[i] < 0f) smoothBuckets[i] = 0f;
            }
            float newCSm = 0f;
            for (int i = 0; i < N_BUCKETS; i++) {
                float diff = rawCircleBuckets[i] - smoothCircleBuckets[i];
                smoothCircleBuckets[i] += diff * (diff > 0f ? upRate : downRate);
                if (smoothCircleBuckets[i] < 0f) smoothCircleBuckets[i] = 0f;
                if (smoothCircleBuckets[i] > newCSm) newCSm = smoothCircleBuckets[i];
            }
            smoothCircleMax = Math.max(1f, newCSm);

            // Flux & spike state
            FluxTrackerAPI flux = ship.getFluxTracker();
            boolean isOverloaded = flux != null && flux.isOverloaded();
            float fluxFrac = (flux != null && !isOverloaded)
                    ? MathUtils.clamp(flux.getCurrFlux() / Math.max(1f, flux.getMaxFlux()), 0f, 1f)
                    : (isOverloaded ? 1f : 0f);

            renderEffSpikeRef    = Math.max(1f, spikeRef * (1f - fluxFrac * FLUX_SENSITIVITY_MAX));
            renderSpikePulseMult = 1f;
            if (!isOverloaded && fluxFrac >= FLUX_SPIKE_T) {
                float urgency = (fluxFrac - FLUX_SPIKE_T) / (1f - FLUX_SPIKE_T);
                float hz = FLUX_SPIKE_MIN_HZ + (FLUX_SPIKE_MAX_HZ - FLUX_SPIKE_MIN_HZ) * urgency;
                spikeFluxPhase += amount * hz * 2f * (float) Math.PI;
                renderSpikePulseMult = 0.30f + 0.70f * ((float) Math.sin(spikeFluxPhase) * 0.5f + 0.5f);
            } else {
                spikeFluxPhase = 0f;
            }

            // Hull pulse state
            renderHullFrac    = ship.getHitpoints() / Math.max(1f, ship.getMaxHitpoints());
            renderCircleAlpha = alpha;

            if (renderHullFrac < HULL_PULSE_T && suppressTimer <= 0f) {
                float urgency  = 1f - (renderHullFrac / HULL_PULSE_T);
                float hz       = PULSE_MIN_HZ + (PULSE_MAX_HZ - PULSE_MIN_HZ) * urgency;
                pulsePhase    += amount * hz * 2f * (float) Math.PI;
                float sinNow   = (float) Math.sin(pulsePhase);
                renderCircleAlpha = (sinNow > 0f) ? alpha : alpha * 0.2f;
                lastSin = sinNow;
                lastBeatSin = sinNow;
            } else {
                pulsePhase = 0f; lastSin = 0f; lastBeatSin = 0f;
            }


            // Overload blackout — updated in unconditional block above
            // Track edge for onset ripple + HUD hide toggle
            if (isOverloaded != wasOverloaded) {
                if (isOverloaded) overloadStartTime = time;
                if (overloadNoiseEnabled) hudControl.onOverloadEdge(engine, isOverloaded);
            }
            wasOverloaded      = isOverloaded;
            renderIsOverloaded = isOverloaded;

            // ─ Shield toggle tracking: fast fade/appear for spikes ────────────────
            ShieldAPI sh = ship.getShield();
            boolean shieldOnNow = sh != null && sh.isOn();
            if (shieldOnNow != renderShieldOn) {
                // Shield just toggled — reset fade to animate the transition
                renderShieldOn = shieldOnNow;
                renderSpikeFadeMult = shieldOnNow ? 0f : 1f;
            }
            // Smooth the fade multiplier
            float spikeFadeTarget = shieldOnNow ? 1f : 0f;
            float fadeSpeed = 8f; // fast transition (≈125ms)
            renderSpikeFadeMult += (spikeFadeTarget - renderSpikeFadeMult)
                    * Math.min(1f, amount * fadeSpeed);

            // ─ Huge shield mode: shield radius > 3× sprite diagonal ──────────────
            //   In huge mode the ring (circle of health) sits SNUG on the ship
            //   sprite, and the spikes are pushed OUTSIDE the shield bubble.
            //   In normal mode both share the standard calcBaseRadius() value.
            float hugeThresh = 3f;
            float spriteR = calcSpriteRadius(ship);
            boolean isHuge = sh != null && sh.getRadius() > 0f
                    && sh.getRadius() > spriteR * hugeThresh;
            hugeShieldMode = isHuge;

            float targetR    = isHuge
                    ? spriteR + SHIELD_GAP                   // ring stays small on the hull
                    : calcBaseRadius(ship);
            currentBaseR += (targetR - currentBaseR) * Math.min(1f, amount * 6f);
            renderBaseR = currentBaseR;

            if (isHuge) {
                renderSpikeBaseR = sh.getRadius() + SHIELD_GAP; // spikes outside huge bubble
            } else {
                renderSpikeBaseR = renderBaseR;
            }
        }

        if (tracked == null) return;
        viewport = engine.getViewport();
        Vector2f loc     = tracked.getLocation();
        float    screenW = Global.getSettings().getScreenWidthPixels();
        float    screenH = Global.getSettings().getScreenHeightPixels();
        float    shipSX  = viewport.convertWorldXtoScreenX(loc.x);
        float    shipSY  = viewport.convertWorldYtoScreenY(loc.y);

        if (!renderIsOverloaded) {
            Vector2f visualCenter = getVisualCenter(tracked);
            ShieldAPI shield = tracked.getShield();
            Vector2f circleCenter = (shield != null && shield.isOn() && !hugeShieldMode) ? shield.getLocation() : visualCenter;
            Vector2f spikeCenter = (shield != null) ? shield.getLocation() : visualCenter;

            glSetup();
            try {
                drawHealthCircle(circleCenter, renderBaseR, renderCircleAlpha, lineWidth, screenW, screenH, shipSX, shipSY);
                if (directionEnabled && tracked != null) {
                    Vector2f vel = tracked.getVelocity();
                    float speed = vel.length();
                    if (speed > 0.1f) {
                        float moveDeg = (float) Math.toDegrees(Math.atan2(vel.y, vel.x));
                        if (moveDeg < 0f) moveDeg += 360f;
                        float arrowAlpha = renderCircleAlpha;
                        if (speed < 50f) {
                            arrowAlpha *= (speed / 50f);
                        }
                        if (arrowAlpha > 0.005f) {
                            float hf = renderHullFrac;
                            Color arrowColor;
                            if      (hf >= 0.50f) arrowColor = lerpColor(YELLOW, baseColor, (hf - 0.50f) / 0.50f);
                            else if (hf >= 0.25f) arrowColor = lerpColor(ORANGE, YELLOW,    (hf - 0.25f) / 0.25f);
                            else                  arrowColor = lerpColor(RED,    ORANGE,     hf           / 0.25f);
                            drawDirectionArrow(circleCenter, renderBaseR, arrowAlpha, lineWidth, arrowColor, moveDeg);
                        }
                    }
                }
                float spikeA = alpha * renderSpikePulseMult * renderSpikeFadeMult;
                if (spikeA > 0.005f) {
                    drawSpikes(spikeCenter, renderSpikeBaseR, spikeA, screenW, screenH, shipSX, shipSY);
                }
            } finally {
                glTeardown();
            }
        }
    }

    /** Screen-space overlays: overload noise and death noise. */
    @Override
    public void renderInUICoords(ViewportAPI vp) {
        if (engine == null) return;
        if (engine.getCombatUI() != null && engine.getCombatUI().isShowingCommandUI()) return;

        boolean drawOverload = overloadNoiseAlpha > 0.01f;
        boolean drawDeath    = deathNoiseAlpha    > 0.01f;
        if (!drawOverload && !drawDeath) return;

        float sw = Global.getSettings().getScreenWidthPixels();
        float sh = Global.getSettings().getScreenHeightPixels();
        screenGLBegin(sw, sh);
        if (drawOverload) drawOverloadNoise(sw, sh);
        if (drawDeath)    drawDeathNoise(sw, sh);
        screenGLEnd();
    }

    // ── Hit recording ─────────────────────────────────────────────────────────

    void recordHit(ShipAPI source, Vector2f fallbackPoint, float dmg, boolean shieldHit) {
        for (HitRecord r : hitRecords) {
            if (r.source == source && r.shieldHit == shieldHit && !r.fighterMissile) {
                r.weight += dmg; return;
            }
        }
        hitRecords.add(new HitRecord(source, fallbackPoint, dmg, shieldHit));
    }

    /**
     * Records a missile hit from a fighter (angle-only, no source tracking).
     * Uses a capped list — replaces the lightest existing fighter record when full.
     */
    void recordFighterMissileHit(Vector2f directionPoint, float dmg, boolean shieldHit) {
        // Count existing fighter missile records
        int count = 0;
        HitRecord weakest = null;
        for (HitRecord r : hitRecords) {
            if (!r.fighterMissile) continue;
            if (r.shieldHit == shieldHit) count++;
            if (weakest == null || r.weight < weakest.weight) weakest = r;
        }
        if (count >= FIGHTER_HIT_CAP) {
            if (weakest != null) weakest.weight += dmg;
            return;
        }
        hitRecords.add(new HitRecord(null, directionPoint, dmg, shieldHit, true));
    }

    // ── Listener management ───────────────────────────────────────────────────

    private void attachListeners(ShipAPI ship) {
        if (listener == null) listener = new PSIDamageListener(this);
        ship.addListener(listener);
        try {
            List<ShipAPI> modules = ship.getChildModulesCopy();
            if (modules != null)
                for (ShipAPI m : modules) if (m != null) m.addListener(listener);
        } catch (Exception ignored) {}
    }

    private void detachListeners() {
        if (tracked == null || listener == null) return;
        try { tracked.removeListener(listener); } catch (Exception ignored) {}
        try {
            List<ShipAPI> modules = tracked.getChildModulesCopy();
            if (modules != null)
                for (ShipAPI m : modules) if (m != null)
                    try { m.removeListener(listener); } catch (Exception ignored2) {}
        } catch (Exception ignored) {}
    }

    // ── Radius ────────────────────────────────────────────────────────────────

    private float calcBaseRadius(ShipAPI ship) {
        // Use the sprite bounding-box diagonal as the base radius — gives a
        // snug fit on the visible hull rather than the often-huge collision
        // radius (especially for modular ships where collision encompasses
        // every child module). Falls back to collisionRadius if the sprite
        // is degenerate or implausibly small.
        SpriteAPI s = ship.getSpriteAPI();
        float collR = ship.getCollisionRadius();
        float hf, hs;
        if (s != null && s.getWidth() > 0f && s.getHeight() > 0f) {
            hf = s.getHeight() * 0.5f;
            hs = s.getWidth()  * 0.5f;
            if (Math.max(hf, hs) < collR * 0.5f) { hf = collR; hs = collR; }
        } else {
            hf = collR; hs = collR;
        }
        float r = (float) Math.sqrt(hf * hf + hs * hs);
        // Cap at collision radius so asymmetric sprites (e.g. ships with large
        // forward gun mounts extending the sprite) don't push the ring off-center.
        r = Math.min(r, collR * 1.1f);

        // If the shield exists, clip the ring inside it (shield off) or push
        // it just outside (shield on) so the indicator never overlaps the
        // active shield bubble.
        ShieldAPI sh = ship.getShield();
        if (sh != null && sh.getRadius() > 0f) {
            if (sh.isOn()) {
                r = sh.getRadius() + SHIELD_GAP;
            } else {
                float cap = sh.getRadius() * 0.92f;
                if (r > cap) r = cap;
            }
        }
        return r * sizeMult;
    }

    private float calcSpikeRef(ShipAPI ship) {
        float shieldEHP = 0f;
        ShieldAPI sh    = ship.getShield();
        if (sh != null && sh.getType() != ShieldAPI.ShieldType.NONE) {
            float fpd = Math.max(0.5f, sh.getFluxPerPointOfDamage());
            shieldEHP = ship.getFluxTracker().getMaxFlux() / fpd;
        }
        // Cap relative to hull HP so battlestations with enormous shield-EHP
        // don't normalise spike weight down to invisibility.
        float hullHP  = Math.max(1f, ship.getMaxHitpoints());
        float capEHP  = hullHP * 6f;
        shieldEHP = Math.min(shieldEHP, capEHP);
        return Math.max(1f, shieldEHP * SPIKE_EHP_FRAC);
    }

    private float calcCircleRef(ShipAPI ship) {
        float hullHP  = ship.getMaxHitpoints();
        float armorHP = (ship.getArmorGrid() != null)
                ? ship.getArmorGrid().getMaxArmorInCell() * 15f : 0f;
        return Math.max(1f, (hullHP + armorHP) * CIRCLE_EHP_FRAC);
    }

    /** Sprite bounding-box diagonal, without shield adjustments. */
    private float calcSpriteRadius(ShipAPI ship) {
        SpriteAPI s = ship.getSpriteAPI();
        float collR = ship.getCollisionRadius();
        float hf, hs;
        if (s != null && s.getWidth() > 0f && s.getHeight() > 0f) {
            hf = s.getHeight() * 0.5f;
            hs = s.getWidth()  * 0.5f;
            if (Math.max(hf, hs) < collR * 0.5f) { hf = collR; hs = collR; }
        } else {
            hf = collR; hs = collR;
        }
        float r = (float) Math.sqrt(hf * hf + hs * hs);
        r = Math.min(r, collR * 1.1f);
        return r * sizeMult;
    }

    private Vector2f getVisualCenter(ShipAPI ship) {
        if (ship == null) return new Vector2f();
        Vector2f center = ship.getShieldCenterEvenIfNoShield();
        if (center == null) return ship.getLocation();
        return center;
    }

    private void drawDirectionArrow(Vector2f c, float r, float a, float lw, Color color, float moveDeg) {
        double rad = Math.toRadians(moveDeg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float tx = c.x + (r + 32f) * cos;
        float ty = c.y + (r + 32f) * sin;
        float d_parallel = 20f;
        float d_perpendicular = 16f;
        float lx = tx - d_parallel * cos - d_perpendicular * sin;
        float ly = ty - d_parallel * sin + d_perpendicular * cos;
        float rx = tx - d_parallel * cos + d_perpendicular * sin;
        float ry = ty - d_parallel * sin - d_perpendicular * cos;
        GL11.glLineWidth(lw);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        GL11.glColor4f(color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f, a);
        GL11.glVertex2f(lx, ly);
        GL11.glVertex2f(tx, ty);
        GL11.glVertex2f(rx, ry);
        GL11.glEnd();
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    /**
     * Two-pass health ring + integrated ripple.
     * Pass 1: hull-colored circle, alpha fades to "gap" at hit angles.
     * Pass 2: ripple wobble at same radius, alpha fades IN at hit angles
     *         (so the wobbly line replaces the circle where damage is incoming).
     */
    private void drawHealthCircle(Vector2f c, float r, float a, float lw,
                                  float screenW, float screenH, float shipSX, float shipSY) {
        float hf = renderHullFrac;
        Color hc;
        if      (hf >= 0.50f) hc = lerpColor(YELLOW, baseColor, (hf - 0.50f) / 0.50f);
        else if (hf >= 0.25f) hc = lerpColor(ORANGE, YELLOW,    (hf - 0.25f) / 0.25f);
        else                  hc = lerpColor(RED,    ORANGE,     hf           / 0.25f);

        // ── Pass 1: solid health circle with gap at hit angles ─────────────────────
        GL11.glLineWidth(lw);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < SG_64; i++) {
            float  deg = i * 5.625f;
            float  vx  = c.x + r * COS_64[i];
            float  vy  = c.y + r * SIN_64[i];

            float arcFade  = computeStatusBarArcFade(deg);
            float edgeFade = elementFade(vx, vy, screenW, screenH);
            float hitW     = MathUtils.clamp(smoothedAt(deg, smoothCircleBuckets) / circleRef, 0f, 1f);
            float gapMult  = 1f - MathUtils.clamp(hitW * 1.3f, 0f, 0.95f);

            GL11.glColor4f(hc.getRed() / 255f, hc.getGreen() / 255f,
                           hc.getBlue() / 255f, MathUtils.clamp(a * arcFade * edgeFade * gapMult, 0f, 1f));
            GL11.glVertex2f(vx, vy);
        }
        GL11.glEnd();
    }

    /** Vanilla FLUX/HULL status bar arc gap (upper-right, ~25°–45°). */
    private float computeStatusBarArcFade(float deg) {
        if (deg < STATUS_SKIP_START - STATUS_SKIP_FADE
                || deg > STATUS_SKIP_END + STATUS_SKIP_FADE) return 1f;
        if (deg >= STATUS_SKIP_START && deg <= STATUS_SKIP_END) return 0f;
        if (deg < STATUS_SKIP_START)
            return MathUtils.clamp((STATUS_SKIP_START - deg) / STATUS_SKIP_FADE, 0f, 1f);
        return MathUtils.clamp((deg - STATUS_SKIP_END) / STATUS_SKIP_FADE, 0f, 1f);
    }

    /**
     * Spikes: exactly 5 per attacker ship (red) — staircase 100%/50%/50%/25%/25%.
     * Fighter missile hits (orange) use angle-only fallback, weight from hr.weight.
     */
    private void drawSpikes(Vector2f c, float r, float a,
                            float screenW, float screenH, float shipSX, float shipSY) {
        if (hitRecords.isEmpty()) return;
        GL11.glLineWidth(Math.max(1.5f, lineWidth * 0.80f));
        for (HitRecord hr : hitRecords) {
            // Process shield hits (red) and fighter missile shield hits (orange)
            if (!hr.shieldHit && !hr.fighterMissile) continue;
            // Fighter missiles that hit hull/armor are handled by drawRipple, not here
            if (hr.fighterMissile && !hr.shieldHit) continue;

            float centralDeg;
            float weight;
            Color spikeColor;

            if (hr.fighterMissile) {
                // Orange: angle from fallback, weight from record directly
                float fdx = hr.fallback.x - c.x, fdy = hr.fallback.y - c.y;
                if (fdx == 0f && fdy == 0f) continue;
                centralDeg = (float) Math.toDegrees(Math.atan2(fdy, fdx));
                if (centralDeg < 0f) centralDeg += 360f;
                weight = MathUtils.clamp(hr.weight / renderEffSpikeRef, 0f, 1f);
                if (weight < SPIKE_MIN_FRAC) continue;
                spikeColor = ORANGE;
            } else {
                // Red: angle from live source position, weight from smooth bucket
                Vector2f from = (hr.source != null && hr.source.isAlive())
                        ? hr.source.getLocation() : hr.fallback;
                float fdx = from.x - c.x, fdy = from.y - c.y;
                if (fdx == 0f && fdy == 0f) continue;
                centralDeg = (float) Math.toDegrees(Math.atan2(fdy, fdx));
                if (centralDeg < 0f) centralDeg += 360f;
                weight = MathUtils.clamp(smoothedAt(centralDeg, smoothBuckets) / renderEffSpikeRef, 0f, 1f);
                if (weight < SPIKE_MIN_FRAC) continue;
                spikeColor = RED;
            }

            float shaped = (float) Math.pow(weight, SPIKE_CONTRAST);
            float maxLen = shaped * SPIKE_MAX_LEN * renderSpikePulseMult;

            GL11.glBegin(GL11.GL_LINES);
            for (int s = -2; s <= 2; s++) {
                float lenFrac  = (s == 0) ? 1.00f : (Math.abs(s) == 1 ? 0.50f : 0.25f);
                float spikeLen = maxLen * lenFrac;
                if (spikeLen < 0.5f) continue;

                float deg  = centralDeg + s * SPIKE_SIDE_DEG;
                double rad = Math.toRadians(deg);
                float  cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
                float  tipX = c.x + (r + SPIKE_GAP + spikeLen) * cos;
                float  tipY = c.y + (r + SPIKE_GAP + spikeLen) * sin;
                float  fade = elementFade(tipX, tipY, screenW, screenH);
                if (fade < 0.01f) continue;

                float spikeA = MathUtils.clamp(a * (0.80f + 0.20f * weight * lenFrac) * fade, 0f, 1f);
                GL11.glColor4f(spikeColor.getRed() / 255f, spikeColor.getGreen() / 255f,
                               spikeColor.getBlue() / 255f, spikeA);
                GL11.glVertex2f(c.x + (r + SPIKE_GAP) * cos, c.y + (r + SPIKE_GAP) * sin);
                GL11.glVertex2f(tipX, tipY);
            }
            GL11.glEnd();
        }
    }



    /** Screen-space CRT noise overlay during overload. */
    private void drawOverloadNoise(float sw, float sh) {
        float a = overloadNoiseAlpha;

        // 1. Dark overlay — 70% black
        GL11.glColor4f(0f, 0f, 0f, a * 0.70f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(0f, 0f); GL11.glVertex2f(sw, 0f);
        GL11.glVertex2f(sw, sh); GL11.glVertex2f(0f, sh);
        GL11.glEnd();

        // 2. Purple/violet horizontal bands — key overload hallmark
        float[] speeds = { sh / 2.5f, sh / 4.2f, sh / 1.8f, sh / 3.1f };
        float[] offsets = { 0f, sh * 0.33f, sh * 0.67f, sh * 0.15f };
        float[] oscFreqs = { 0.8f, 1.4f, 0.5f, 1.9f };
        float[] oscAmps = { 40f, 60f, 30f, 50f };
        Random scrollRand = new Random((long) (time * 60f));
        long timeSeed = (long) (time * 1.0f);
        for (int i = 0; i < 4; i++) {
            float osc = (float) Math.sin(time * oscFreqs[i] + i * 1.7f) * oscAmps[i];
            float baseY = (sh - (time * speeds[i] + offsets[i] + osc)) % sh;
            if (baseY < 0f) baseY += sh;
            float jitter = (scrollRand.nextFloat() - 0.5f) * 15f;
            float ly = baseY + jitter;
            float lineA = (0.15f + scrollRand.nextFloat() * 0.10f) * a;
            Random lineRand = new Random((long) (i + timeSeed * 10));
            int style = lineRand.nextInt(4);
            if (style == 0) {
                GL11.glLineWidth(3.5f);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glColor4f(0.55f, 0.05f, 0.90f, lineA);
                GL11.glVertex2f(0f, ly); GL11.glVertex2f(sw, ly);
                GL11.glEnd();
            } else if (style == 1) {
                GL11.glLineWidth(1.5f);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glColor4f(0.50f, 0.05f, 0.92f, lineA * 0.9f);
                GL11.glVertex2f(0f, ly - 3f); GL11.glVertex2f(sw, ly - 3f);
                GL11.glVertex2f(0f, ly + 3f); GL11.glVertex2f(sw, ly + 3f);
                GL11.glEnd();
            } else if (style == 2) {
                GL11.glLineWidth(7.0f);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glColor4f(0.60f, 0.02f, 0.95f, lineA * 0.4f);
                GL11.glVertex2f(0f, ly); GL11.glVertex2f(sw, ly);
                GL11.glEnd();
                GL11.glLineWidth(2.0f);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glColor4f(0.85f, 0.60f, 1.00f, lineA * 1.1f);
                GL11.glVertex2f(0f, ly); GL11.glVertex2f(sw, ly);
                GL11.glEnd();
            } else {
                GL11.glLineWidth(2.5f);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glColor4f(0.55f, 0.05f, 0.90f, lineA);
                float segW = sw / 7f;
                for (int s = 0; s < 7; s++) {
                    if (s % 2 == 0) {
                        GL11.glVertex2f(s * segW, ly);
                        GL11.glVertex2f((s + 1) * segW, ly);
                    }
                }
                GL11.glEnd();
            }
        }
        GL11.glLineWidth(2.0f);
        GL11.glBegin(GL11.GL_LINES);
        for (int i = 0; i < 3; i++) {
            float ly = scrollRand.nextFloat() * sh;
            float lineA = (0.05f + scrollRand.nextFloat() * 0.08f) * a;
            GL11.glColor4f(0.55f, 0.05f, 0.90f, lineA);
            GL11.glVertex2f(0f, ly); GL11.glVertex2f(sw, ly);
        }
        GL11.glEnd();
        GL11.glLineWidth(1f);

        // 3. Wave-distorted scanlines — grayscale
        Random scanRand = new Random((long) (time * 8f));
        float waveOff = time * 2.5f;
        GL11.glBegin(GL11.GL_QUADS);
        for (int y = 0; y < (int) sh; y += 3) {
            float disp = (float) Math.sin((y * 0.015f) + waveOff) * 4f * a;
            float x0   = disp, x1 = sw + disp;
            float luma = scanRand.nextFloat() * 0.22f * a;
            GL11.glColor4f(luma, luma, luma, luma);
            GL11.glVertex2f(x0, y); GL11.glVertex2f(x1, y);
            GL11.glVertex2f(x1, y + 2f); GL11.glVertex2f(x0, y + 2f);
        }
        GL11.glEnd();

        // 4. Block noise — grayscale 2-3 px squares
        Random blockRand = new Random((long) (time * 6f));
        GL11.glBegin(GL11.GL_QUADS);
        int blockCount = (int) (120 * a);
        for (int i = 0; i < blockCount; i++) {
            float bx   = blockRand.nextFloat() * sw;
            float by   = blockRand.nextFloat() * sh;
            float bs   = 2f + blockRand.nextFloat() * 1.5f;
            float bA   = (0.25f + blockRand.nextFloat() * 0.35f) * a;
            float luma = 0.6f + blockRand.nextFloat() * 0.4f;
            GL11.glColor4f(luma, luma, luma, bA);
            GL11.glVertex2f(bx, by); GL11.glVertex2f(bx + bs, by);
            GL11.glVertex2f(bx + bs, by + bs); GL11.glVertex2f(bx, by + bs);
        }
        GL11.glEnd();

        // 5. Monochrome static pixels — dense fine grain
        Random pixRand = new Random((long) (time * 20f));
        GL11.glPointSize(0.8f);
        GL11.glBegin(GL11.GL_POINTS);
        int pixCount = (int) (1800 * a);
        for (int i = 0; i < pixCount; i++) {
            float px   = pixRand.nextFloat() * sw;
            float py   = pixRand.nextFloat() * sh;
            float luma = 0.4f + 0.6f * pixRand.nextFloat();
            GL11.glColor4f(luma, luma, luma, a * luma);
            GL11.glVertex2f(px, py);
        }
        GL11.glEnd();
    }

    /**
     * Death noise overlay: dark vignette + random-color static.
     * Plays from ship destruction until the player boards a new ship.
     */
    private void drawDeathNoise(float sw, float sh) {
        float a = deathNoiseAlpha;

        // 1. Dark overlay — 85% black (same level as overload)
        GL11.glColor4f(0f, 0f, 0f, a * 0.85f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(0f, 0f); GL11.glVertex2f(sw, 0f);
        GL11.glVertex2f(sw, sh); GL11.glVertex2f(0f, sh);
        GL11.glEnd();

        // 2. Monochrome static pixels
        Random pixRand = new Random((long) (time * 20f));
        GL11.glPointSize(0.8f);
        GL11.glBegin(GL11.GL_POINTS);
        int pixCount = (int) (1800 * a);
        for (int i = 0; i < pixCount; i++) {
            float px   = pixRand.nextFloat() * sw;
            float py   = pixRand.nextFloat() * sh;
            float luma = 0.4f + 0.6f * pixRand.nextFloat();
            GL11.glColor4f(luma, luma, luma, a * luma);
            GL11.glVertex2f(px, py);
        }
        GL11.glEnd();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────


    /**
     * Alpha multiplier for world-space elements: screen-edge fade on all 4 edges.
     * Status bar exclusion is now handled via an angular arc gap in drawHealthCircle.
     */
    private float elementFade(float worldX, float worldY, float screenW, float screenH) {
        float sx = viewport.convertWorldXtoScreenX(worldX);
        float sy = viewport.convertWorldYtoScreenY(worldY);
        float eL = MathUtils.clamp((sx - EDGE_MARGIN) / EDGE_WIDTH, 0f, 1f);
        float eR = MathUtils.clamp((screenW - EDGE_MARGIN - sx) / EDGE_WIDTH, 0f, 1f);
        float eB = MathUtils.clamp((sy - EDGE_MARGIN) / EDGE_WIDTH, 0f, 1f);
        float eT = MathUtils.clamp((screenH - EDGE_MARGIN - sy) / EDGE_WIDTH, 0f, 1f);
        return Math.min(Math.min(eL, eR), Math.min(eB, eT));
    }

    /** Legacy overload — delegates to 4-arg version; status bar exclusion replaced by arc gap. */
    private float elementFade(float worldX, float worldY,
                              float screenW, float screenH,
                              float shipSX, float shipSY) {
        return elementFade(worldX, worldY, screenW, screenH);
    }



    private float smoothedAt(float deg, float[] buckets) {
        float idxF = deg / (360f / N_BUCKETS);
        int   lo   = ((int) Math.floor(idxF)) % N_BUCKETS;
        if (lo < 0) lo += N_BUCKETS;
        float frac = idxF - (float) Math.floor(idxF);
        return buckets[lo] * (1f - frac) + buckets[(lo + 1) % N_BUCKETS] * frac;
    }

    private static Color lerpColor(Color a, Color b, float t) {
        t = MathUtils.clamp(t, 0f, 1f);
        return new Color(
                (int) (a.getRed()   + (b.getRed()   - a.getRed())   * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
    }

    private static boolean isExcludedHull(ShipAPI ship) {
        String id = ship.getHullSpec().getHullId();
        for (String ex : EXCLUDED_HULLS) if (ex.equals(id)) return true;
        return false;
    }

    private static int   clampIdx(int i)                   { return ((i % N_BUCKETS) + N_BUCKETS) % N_BUCKETS; }

    // ── GL state helpers ──────────────────────────────────────────────────────

    private void glSetup() {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glOrtho(viewport.getLLX(), viewport.getLLX() + viewport.getVisibleWidth(),
                     viewport.getLLY(), viewport.getLLY() + viewport.getVisibleHeight(), -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_LINE_SMOOTH); GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void glTeardown() {
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPopMatrix();
        GL11.glPopAttrib();
    }

    private static void screenGLBegin(float sw, float sh) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glOrtho(0, sw, 0, sh, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void screenGLEnd() {
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPopMatrix();
        GL11.glPopAttrib();
    }

    // ── Damage listener ───────────────────────────────────────────────────────

    private static class PSIDamageListener implements DamageTakenModifier {
        private final DamageMarkerCombatPlugin plugin;
        PSIDamageListener(DamageMarkerCombatPlugin p) { this.plugin = p; }

        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target,
                                        DamageAPI damage, Vector2f point, boolean shieldHit) {
            if (damage == null || point == null) return null;
            float dmg = Math.max(1f, damage.getDamage());
            ShipAPI src = null;
            WeaponAPI weapon = null;

            if (param instanceof DamagingProjectileAPI) {
                DamagingProjectileAPI p = (DamagingProjectileAPI) param;
                src    = p.getSource();
                weapon = p.getWeapon();
                if (weapon != null && (weapon.hasAIHint(WeaponAPI.AIHints.PD)
                        || weapon.hasAIHint(WeaponAPI.AIHints.PD_ONLY)
                        || weapon.hasAIHint(WeaponAPI.AIHints.PD_ALSO))) return null;
            } else if (param instanceof BeamAPI) {
                BeamAPI b = (BeamAPI) param;
                src    = b.getSource();
                weapon = b.getWeapon();
                if (weapon != null && (weapon.hasAIHint(WeaponAPI.AIHints.PD)
                        || weapon.hasAIHint(WeaponAPI.AIHints.PD_ONLY)
                        || weapon.hasAIHint(WeaponAPI.AIHints.PD_ALSO))) return null;
            }
            if (src == null) return null;  // collision / debris
            if (src.getOwner() == target.getOwner()) return null;

            // Fighter: only register missile (torpedo/rocket) hits, angle-only.
            if (src.getHullSize() == ShipAPI.HullSize.FIGHTER) {
                if (weapon != null && weapon.getType() == WeaponAPI.WeaponType.MISSILE) {
                    // Compute direction from player ship to the fighter at impact time.
                    // Store a point far in that direction so angle is accurate.
                    if (target instanceof ShipAPI) {
                        ShipAPI tgt = (ShipAPI) target;
                        float dx = src.getLocation().x - tgt.getLocation().x;
                        float dy = src.getLocation().y - tgt.getLocation().y;
                        float len = (float) Math.sqrt(dx * dx + dy * dy);
                        if (len > 0.1f) {
                            Vector2f dir = new Vector2f(
                                    tgt.getLocation().x + dx / len * 50000f,
                                    tgt.getLocation().y + dy / len * 50000f);
                            plugin.recordFighterMissileHit(dir, dmg, shieldHit);
                        }
                    }
                }
                return null; // fighters never feed the regular hit system
            }

            plugin.recordHit(src, point, dmg, shieldHit);
            return null;
        }
    }
}
