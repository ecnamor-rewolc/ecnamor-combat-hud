package ecnamor.hud.arcs;

/*
 * ──────────────────────────────────────────────────────────────────────────────
 *  Thick weapon firing arc overlay for the player ship.
 *
 *  -- TUNING --
 *    Arc resolution         : ARC_SEGMENTS
 *    Vanilla preset colors  : VANILLA_GREEN, VANILLA_BLUE, VANILLA_YELLOW
 *    Defaults overridden by LunaLib :
 *      lineWidth=2.0, arcAlpha=0.55, facingAlpha=0.75
 *      tickStep=250, tickLen=48
 *
 *    Arcs hidden when: no weapon group is selected, or ship is on autopilot.
 *    Weapons with 360-degree arc, hidden slots, or decorative type are skipped.
 *
 *    Group-disabled glitch effect triggers when >= half+1 eligible weapons in
 *    the selected group are disabled. Eligible = non-system, non-decorative,
 *    non-hidden-slot. Arc color shifts to GLITCH_ORANGE when glitching. Tune:
 *      GLITCH_FLICKER_HZ      : flicker rate (halved from original 9.0)
 *      GLITCH_TEAR_MAX_PX     : screen-tear offset magnitude
 *      GLITCH_NOISE_COUNT     : static noise dot count
 *      GLITCH_NOISE_RADIUS    : dot scatter radius
 *
 *  Runtime toggles in LunaSettings.csv under tab "Weapon Arcs".
 * ──────────────────────────────────────────────────────────────────────────────
 */

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.input.InputEventAPI;
import org.lazywizard.lazylib.opengl.DrawUtils;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.List;
import java.util.Random;

public class ThickArcsCombatPlugin extends BaseEveryFrameCombatPlugin {

    private static final String MOD_ID            = "ecnamor_combat_hud";
    private static final String PERSIST_TOGGLE_KEY = "ecnamor_combat_hud_arcs_toggle";
    private static final int    ARC_SEGMENTS       = 36;

    private static final Color VANILLA_GREEN  = new Color( 70, 255, 110);
    private static final Color VANILLA_BLUE   = new Color( 70, 200, 255);
    private static final Color VANILLA_YELLOW = new Color(255, 220,  70);
    private static final Color GLITCH_ORANGE  = new Color(255, 150,   0);

    private static final String PRESET_GREEN  = "Vanilla Green";
    private static final String PRESET_BLUE   = "Vanilla Blue";
    private static final String PRESET_YELLOW = "Vanilla Yellow";

    private static final int DEFAULT_KEYCODE = 48;

    // Per-weapon disabled glitch tuning
    private static final float GLITCH_FLICKER_HZ  = 4.5f;
    private static final float GLITCH_FLICKER_MIN = 0.40f;
    private static final float GLITCH_TEAR_MAX_WU = 20f;
    private static final float GLITCH_TEAR_HZ     = 8.0f;

    private CombatEngineAPI engine;
    private float           time       = 0f;
    private final Random    glitchRng  = new Random();

    private boolean hasAutoToggled = false;
    private com.fs.starfarer.api.combat.WeaponGroupAPI lastSelectedGroup = null;
    private ShipAPI lastTrackedShip = null;

    // Settings (defaults match LunaSettings.csv).
    private boolean enabled     = true;
    private Color   color       = VANILLA_YELLOW;
    private float   lineWidth   = 1.0f;
    private float   arcAlpha    = 0.45f;
    private float   facingAlpha = 0.55f;

    private boolean toggleMode  = true;
    private boolean toggleState = true;
    private boolean wasKeyDown  = false;

    private int holdKey    = DEFAULT_KEYCODE;
    private int holdKeyAlt = -1;

    private boolean showTicks  = true;
    private float   tickStep   = 250f;
    private float   tickLen    = 48f;

    private boolean showDamaged = true;


    @Override
    public void init(CombatEngineAPI engine) {
        this.engine     = engine;
        this.time       = 0f;
        this.wasKeyDown = false;
        this.hasAutoToggled = false;
        this.lastSelectedGroup = null;
        this.lastTrackedShip = null;
        loadSettings();
        restoreToggleState();
    }

    private void loadSettings() {
        if (!Global.getSettings().getModManager().isModEnabled("lunalib")) return;
        try {
            Boolean b;
            Double  d;
            Color   c;
            String  s;

            b = lunalib.lunaSettings.LunaSettings.getBoolean(MOD_ID, "arcs_enabled");
            if (b != null) enabled = b;

            String preset = lunalib.lunaSettings.LunaSettings.getString(MOD_ID, "arcs_color_preset");
            if      (PRESET_GREEN .equals(preset)) color = VANILLA_GREEN;
            else if (PRESET_BLUE  .equals(preset)) color = VANILLA_BLUE;
            else if (PRESET_YELLOW.equals(preset)) color = VANILLA_YELLOW;
            else {
                c = lunalib.lunaSettings.LunaSettings.getColor(MOD_ID, "arcs_color");
                if (c != null) color = c;
            }

            d = lunalib.lunaSettings.LunaSettings.getDouble(MOD_ID, "arcs_line_width");
            if (d != null) lineWidth = MathUtils.clamp(d.floatValue(), 0.5f, 16f);

            d = lunalib.lunaSettings.LunaSettings.getDouble(MOD_ID, "arcs_arc_alpha");
            if (d != null) arcAlpha = MathUtils.clamp(d.floatValue(), 0f, 1f);

            d = lunalib.lunaSettings.LunaSettings.getDouble(MOD_ID, "arcs_facing_alpha");
            if (d != null) facingAlpha = MathUtils.clamp(d.floatValue(), 0f, 1f);

            Integer kc = lunalib.lunaSettings.LunaSettings.getInt(MOD_ID, "arcs_hold_key");
            if (kc != null && kc > 0) {
                holdKey = kc;
                if (kc == Keyboard.KEY_LCONTROL || kc == Keyboard.KEY_RCONTROL) {
                    holdKey = Keyboard.KEY_LCONTROL; holdKeyAlt = Keyboard.KEY_RCONTROL;
                } else if (kc == Keyboard.KEY_LMENU || kc == Keyboard.KEY_RMENU) {
                    holdKey = Keyboard.KEY_LMENU; holdKeyAlt = Keyboard.KEY_RMENU;
                } else if (kc == Keyboard.KEY_LSHIFT || kc == Keyboard.KEY_RSHIFT) {
                    holdKey = Keyboard.KEY_LSHIFT; holdKeyAlt = Keyboard.KEY_RSHIFT;
                } else {
                    holdKeyAlt = -1;
                }
            } else {
                holdKey = -1; holdKeyAlt = -1;
            }

            s = lunalib.lunaSettings.LunaSettings.getString(MOD_ID, "arcs_trigger_mode");
            if (s != null) toggleMode = "Toggle".equals(s);

            b = lunalib.lunaSettings.LunaSettings.getBoolean(MOD_ID, "arcs_show_ticks");
            if (b != null) showTicks = b;

            d = lunalib.lunaSettings.LunaSettings.getDouble(MOD_ID, "arcs_tick_step");
            if (d != null) tickStep = MathUtils.clamp(d.floatValue(), 50f, 2000f);

            b = lunalib.lunaSettings.LunaSettings.getBoolean(MOD_ID, "arcs_show_damaged");
            if (b != null) showDamaged = b;

        } catch (Exception ignored) {}
    }

    private void restoreToggleState() {
        try {
            if (Global.getSector() == null) return;
            java.util.Map<String, Object> data = Global.getSector().getPersistentData();
            Object saved = data.get(PERSIST_TOGGLE_KEY);
            if (saved instanceof Boolean) toggleState = (Boolean) saved;
        } catch (Exception ignored) {}
    }

    private void saveToggleState() {
        try {
            if (Global.getSector() == null) return;
            Global.getSector().getPersistentData().put(PERSIST_TOGGLE_KEY, toggleState);
        } catch (Exception ignored) {}
    }

    private boolean isKeyDown() {
        if (holdKey <= 0) return false;
        if (Keyboard.isKeyDown(holdKey)) return true;
        if (holdKeyAlt > 0 && Keyboard.isKeyDown(holdKeyAlt)) return true;
        return false;
    }

    private void checkInput() {
        if (holdKey <= 0) return;
        if (!toggleMode) {
            toggleState = isKeyDown();
            return;
        }
        boolean down = isKeyDown();
        if (down && !wasKeyDown) {
            toggleState = !toggleState;
            saveToggleState();
        }
        wasKeyDown = down;
    }

    private boolean shouldShow() {
        return toggleState;
    }

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        if (!enabled || engine == null) return;

        checkInput();

        if (hasAutoToggled) return;
        if (engine.isUIShowingDialog()) return;
        if (!engine.isSimulation() && !engine.isUIShowingHUD()) return;
        if (time < 0.2f) return;

        ShipAPI player = engine.getPlayerShip();
        if (player == null || !player.isAlive() || player.isHulk()) return;

        if (shouldShow()) {
            if (engine.getCombatUI() != null && engine.getCombatUI().areWeaponArcsOn()) {
                hasAutoToggled = true;
                try {
                    InputEventAPI event = (InputEventAPI) java.lang.reflect.Proxy.newProxyInstance(
                        InputEventAPI.class.getClassLoader(),
                        new Class<?>[] { InputEventAPI.class },
                        new java.lang.reflect.InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                                String name = method.getName();
                                if ("isConsumed".equals(name)) return Boolean.FALSE;
                                if ("isKeyDownEvent".equals(name)) return Boolean.TRUE;
                                if ("isKeyUpEvent".equals(name)) return Boolean.FALSE;
                                if ("isKeyboardEvent".equals(name)) return Boolean.TRUE;
                                if ("isMouseEvent".equals(name)) return Boolean.FALSE;
                                if ("getEventClass".equals(name)) return com.fs.starfarer.api.input.InputEventClass.KEYBOARD_EVENT;
                                if ("getEventType".equals(name)) return com.fs.starfarer.api.input.InputEventType.KEY_DOWN;
                                if ("getEventValue".equals(name)) return 41; // Keyboard.KEY_GRAVE
                                if ("getEventChar".equals(name)) return '`';
                                if (method.getReturnType() == boolean.class) return Boolean.FALSE;
                                if (method.getReturnType() == int.class) return 0;
                                if (method.getReturnType() == float.class) return 0f;
                                return null;
                            }
                        }
                    );
                    events.add(event);
                } catch (Throwable ignored) {}
            } else {
                hasAutoToggled = true;
            }
        }
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (!enabled || engine == null) return;
        if (engine.isUIShowingDialog()) return;
        if (engine.getCombatUI() != null && engine.getCombatUI().isShowingCommandUI()) return;
        if (!engine.isSimulation() && !engine.isUIShowingHUD()) return;

        time += amount;

        if (!shouldShow()) return;

        ShipAPI player = engine.getPlayerShip();
        if (player == null || !player.isAlive() || player.isHulk()) return;

        // Hide when on autopilot
        if (player.getShipAI() != null) return;

        // Caching and fallback logic for weapon groups
        if (player != lastTrackedShip) {
            lastTrackedShip = player;
            lastSelectedGroup = null;
        }

        com.fs.starfarer.api.combat.WeaponGroupAPI activeGroup = player.getSelectedGroupAPI();
        if (activeGroup != null) {
            lastSelectedGroup = activeGroup;
        }

        com.fs.starfarer.api.combat.WeaponGroupAPI groupToDraw = (activeGroup != null) ? activeGroup : lastSelectedGroup;
        if (groupToDraw == null) return;

        List<WeaponAPI> weapons = groupToDraw.getWeaponsCopy();
        if (weapons == null || weapons.isEmpty()) return;

        // Per-weapon glitch: compute flicker once, apply per-weapon if disabled.
        float flickerAlphaMult = 1f;
        if (showDamaged) {
            float flicker = (float) Math.sin(time * GLITCH_FLICKER_HZ * Math.PI * 2.0);
            flickerAlphaMult = GLITCH_FLICKER_MIN
                    + (1f - GLITCH_FLICKER_MIN) * (0.5f + 0.5f * flicker);
            glitchRng.setSeed((long) (time * 30f));
            if (glitchRng.nextFloat() < 0.10f) flickerAlphaMult *= 0.25f;
        }

        ViewportAPI vp = engine.getViewport();
        glSetup(vp);
        try {
            GL11.glLineWidth(lineWidth);

            float cr = color.getRed()   / 255f;
            float cg = color.getGreen() / 255f;
            float cb = color.getBlue()  / 255f;

            for (WeaponAPI w : weapons) {
                if (w == null) continue;
                if (w.getType() == WeaponType.SYSTEM) continue;
                if (w.getType() == WeaponType.DECORATIVE) continue;
                if (w.getSlot() != null && w.getSlot().isHidden()) continue;
                boolean wDisabled = showDamaged && w.isDisabled();
                if (wDisabled) {
                    long slotHash = (w.getSlot() != null) ? (long) w.getSlot().getId().hashCode() : 0L;
                    glitchRng.setSeed((long) (time * GLITCH_TEAR_HZ) * 31L + slotHash);
                    float tearX = GLITCH_TEAR_MAX_WU * (glitchRng.nextFloat() * 2f - 1f);
                    GL11.glPushMatrix();
                    GL11.glTranslatef(tearX, 0f, 0f);
                }
                drawArc(w, cr, cg, cb, wDisabled ? flickerAlphaMult : 1f, wDisabled);
                if (wDisabled) GL11.glPopMatrix();
            }
        } finally {
            glTeardown();
        }
    }

    private void drawArc(WeaponAPI w, float cr, float cg, float cb,
                         float alphaMult, boolean glitching) {
        Vector2f loc     = w.getLocation();
        float    range   = w.getRange();
        float    arcDeg  = w.getArc();
        float arcFacing  = w.getArcFacing();
        float shipFace   = w.getShip().getFacing();

        float halfArc  = arcDeg * 0.5f;
        float startDeg = shipFace + arcFacing - halfArc;
        float endDeg   = shipFace + arcFacing + halfArc;
        float spanDeg  = endDeg - startDeg;

        // Use orange when glitching; normal color otherwise
        float dr = glitching ? GLITCH_ORANGE.getRed()   / 255f : cr;
        float dg = glitching ? GLITCH_ORANGE.getGreen() / 255f : cg;
        float db = glitching ? GLITCH_ORANGE.getBlue()  / 255f : cb;

        float baseA = arcAlpha * alphaMult;

        GL11.glColor4f(dr, dg, db, baseA);

        // Arc edges
        if (arcDeg < 359f) {
            GL11.glBegin(GL11.GL_LINES);
            radialLine(loc, range, startDeg);
            radialLine(loc, range, endDeg);
            GL11.glEnd();
        }

        // Arc curve
        float drawSpan = Math.min(spanDeg, 360f);
        if (drawSpan > 0.5f) {
            DrawUtils.drawArc(loc.x, loc.y, range, startDeg, drawSpan, ARC_SEGMENTS, false);
        }

        // Distance ticks
        if (showTicks) {
            drawTicks(loc, range, startDeg, endDeg, alphaMult, dr, dg, db);
        }

        // Facing line
        float faceA = facingAlpha * alphaMult;
        GL11.glColor4f(dr, dg, db, faceA);
        GL11.glBegin(GL11.GL_LINES);
        radialLine(loc, range, w.getCurrAngle());
        GL11.glEnd();
    }

    private void drawTicks(Vector2f loc, float range, float startDeg, float endDeg,
                           float alphaMult, float dr, float dg, float db) {
        if (range < tickStep * 0.5f) return;
        int rings = (int) (range / tickStep);
        if (rings < 1) return;

        GL11.glLineWidth(lineWidth * 1.6f); // ticks visibly wider than arc lines

        for (int r = 1; r <= rings; r++) {
            float radius = r * tickStep;
            if (radius > range) break;

            float midDeg = (startDeg + endDeg) * 0.5f;
            double midRad = Math.toRadians(midDeg);
            float mx = loc.x + radius * (float) Math.cos(midRad);
            float my = loc.y + radius * (float) Math.sin(midRad);

            float nx = (float) Math.cos(midRad);
            float ny = (float) Math.sin(midRad);

            double tRad = Math.toRadians(midDeg + 90f);
            float tx = (float) Math.cos(tRad);
            float ty = (float) Math.sin(tRad);

            float halfLen = tickLen * 0.5f;
            float tickA   = arcAlpha * 0.85f * alphaMult;

            GL11.glColor4f(dr, dg, db, tickA);
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex2f(mx - tx * halfLen, my - ty * halfLen);
            GL11.glVertex2f(mx + tx * halfLen, my + ty * halfLen);
            GL11.glVertex2f(mx, my);
            GL11.glVertex2f(mx + nx * tickLen * 0.4f, my + ny * tickLen * 0.4f);
            GL11.glEnd();
        }

        GL11.glLineWidth(lineWidth); // restore arc line width
    }

    private static void radialLine(Vector2f loc, float range, float deg) {
        double rad = Math.toRadians(deg);
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
        GL11.glVertex2f(loc.x + 8f * cos, loc.y + 8f * sin);
        GL11.glVertex2f(loc.x + range * cos, loc.y + range * sin);
    }

    private static void glSetup(ViewportAPI vp) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glOrtho(vp.getLLX(), vp.getLLX() + vp.getVisibleWidth(),
                     vp.getLLY(), vp.getLLY() + vp.getVisibleHeight(), -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
    }

    private static void glTeardown() {
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPopMatrix();
        GL11.glPopAttrib();
    }
}
