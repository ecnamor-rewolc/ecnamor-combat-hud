package ecnamor.hud.common;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import org.magiclib.ReflectionUtils;

/**
 * Force-hides vanilla combat HUD by toggling {@code CombatState.hideHud}
 * (same boolean the F11 keybind flips).
 * <p>
 * Uses MagicLib's {@link ReflectionUtils} which runs reflection inside its own
 * (non-sandboxed) classloader, bypassing Starsector's script reflection ban.
 * <p>
 * Strategy:
 * - Find CombatEngine's private field of a type with {@code isHideHud()} method
 *   (= the CombatState reference). Cached per-engine.
 * - On overload start: capture current hideHud value, force {@code true}.
 * - On overload end: restore captured value.
 * - Manual user F11 presses during overload are preserved (we only set on edge transitions).
 */
public final class HudControl {

    private CombatEngineAPI cachedEngine;
    private Object          cachedState;
    private Boolean         savedPref;

    public HudControl() {}

    /** Call when overloaded state transitions (true → overload start, false → end). */
    public void onOverloadEdge(CombatEngineAPI engine, boolean nowOverloaded) {
        if (engine == null) return;
        try {
            Object cs = getCombatState(engine);
            if (cs == null) return;

            if (nowOverloaded) {
                if (savedPref == null) {
                    Object curr = ReflectionUtils.get("hideHud", cs);
                    savedPref = (curr instanceof Boolean) ? (Boolean) curr : Boolean.FALSE;
                }
                ReflectionUtils.set("hideHud", cs, Boolean.TRUE);
            } else {
                if (savedPref != null) {
                    ReflectionUtils.set("hideHud", cs, savedPref);
                    savedPref = null;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private Object getCombatState(CombatEngineAPI engine) {
        if (engine == cachedEngine && cachedState != null) return cachedState;
        try {
            ReflectionUtils.ReflectedField f =
                ReflectionUtils.INSTANCE.findFieldWithMethodName(engine, "isHideHud");
            if (f != null) {
                Object cs = f.get(engine);
                if (cs != null) {
                    cachedEngine = engine;
                    cachedState  = cs;
                    return cs;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
