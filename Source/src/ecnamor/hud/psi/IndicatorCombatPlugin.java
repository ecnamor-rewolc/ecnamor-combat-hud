package ecnamor.hud.psi;

/*
 * ──────────────────────────────────────────────────────────────────────────────
 *  Simple player-ship marker — ring or corner brackets, static or pulsing.
 *  Purely cosmetic: distinguishes the player ship from allies / enemies in
 *  the heat of battle. No damage reaction or directional information.
 *
 *  -- TUNING --
 *    Bracket offset multiplier : BRACKET_OFFSET_MULT
 *    Vanilla preset colors     : VANILLA_GREEN, VANILLA_BLUE
 *
 *  Runtime toggles in LunaSettings.csv under tab "Ship Indicator".
 * ──────────────────────────────────────────────────────────────────────────────
 */

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.List;

public class IndicatorCombatPlugin extends BaseEveryFrameCombatPlugin {

    private static final String MOD_ID = "ecnamor_combat_hud";

    private static final String STYLE_RING             = "Ring";
    private static final String STYLE_PULSING          = "Pulsing Ring";
    private static final String STYLE_BRACKETS         = "Corner Brackets";
    private static final String STYLE_PULSING_BRACKETS = "Pulsing Brackets";

    // Brackets sit a touch outside the sprite bounding box so the L-arms don't
    // visually clip the hull edges.  Multiplicative so the offset scales with
    // ship size — frigates get ~3 wu, capitals get ~12 wu.
    private static final float BRACKET_OFFSET_MULT = 1.10f;

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

    // Vanilla Starsector UI colors (from starsector-core/data/config/settings.json).
    // textFriendColor / mountGreenColor and textNeutralColor / mountBlueColor —
    // the two iconic UI colors used across the vanilla HUD.
    public static final Color VANILLA_GREEN = new Color(155, 255,   0);
    public static final Color VANILLA_BLUE  = new Color( 70, 200, 255);

    private static final String PRESET_GREEN  = "Vanilla Green";
    private static final String PRESET_BLUE   = "Vanilla Blue";
    private static final String PRESET_CUSTOM = "Custom";

    private CombatEngineAPI engine;
    private ViewportAPI viewport;
    private float time = 0f;

    // Default settings — used when LunaLib is absent
    private boolean enabled        = false;
    private String  style          = STYLE_PULSING;
    private Color   color          = VANILLA_BLUE;
    private float   alpha          = 0.75f;
    private float   sizeMult       = 1.0f;
    private float   lineWidth      = 3.0f;
    private boolean showWithoutHUD = true;
    private boolean directionEnabled = true;

    // -------------------------------------------------------------------------

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        this.time = 0f;
        loadSettings();
    }

    private void loadSettings() {
        if (!Global.getSettings().getModManager().isModEnabled("lunalib")) return;
        try {
            Boolean b;
            Double  d;
            String  s;
            Color   c;

            b = lunalib.lunaSettings.LunaSettings.getBoolean(MOD_ID, "psi_enabled");
            if (b != null) enabled = b;

            s = lunalib.lunaSettings.LunaSettings.getString(MOD_ID, "psi_style");
            if (s != null && !s.isEmpty()) style = s;

            // Color preset: pick a vanilla UI color, or fall through to the custom picker.
            String preset = lunalib.lunaSettings.LunaSettings.getString(MOD_ID, "psi_color_preset");
            if (PRESET_GREEN.equals(preset)) {
                color = VANILLA_GREEN;
            } else if (PRESET_BLUE.equals(preset)) {
                color = VANILLA_BLUE;
            } else {
                // Custom (or preset field missing — same fallback)
                c = lunalib.lunaSettings.LunaSettings.getColor(MOD_ID, "psi_color");
                if (c != null) color = c;
            }

            d = lunalib.lunaSettings.LunaSettings.getDouble(MOD_ID, "psi_alpha");
            if (d != null) alpha = clamp(d.floatValue(), 0f, 1f);

            d = lunalib.lunaSettings.LunaSettings.getDouble(MOD_ID, "psi_size_mult");
            if (d != null) sizeMult = clamp(d.floatValue(), 0.1f, 4f);

            d = lunalib.lunaSettings.LunaSettings.getDouble(MOD_ID, "psi_line_width");
            if (d != null) lineWidth = clamp(d.floatValue(), 0.5f, 16f);

            b = lunalib.lunaSettings.LunaSettings.getBoolean(MOD_ID, "psi_show_without_hud");
            if (b != null) showWithoutHUD = b;

            b = lunalib.lunaSettings.LunaSettings.getBoolean(MOD_ID, "psi_direction_enabled");
            if (b != null) directionEnabled = b;

        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null) return;
        time += amount;

        if (!enabled) return;

        ShipAPI ship = engine.getPlayerShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) return;

        // Hide during dialogs and when the battle map / command UI is open
        if (engine.isUIShowingDialog()) return;
        if (engine.getCombatUI() != null && engine.getCombatUI().isShowingCommandUI()) return;

        if (!showWithoutHUD && !engine.isUIShowingHUD()) return;

        viewport = engine.getViewport();

        Vector2f loc = getVisualCenter(ship);
        Dims     dim = calcDims(ship);

        boolean pulsing = STYLE_PULSING.equals(style)
                       || STYLE_PULSING_BRACKETS.equals(style);
        boolean brackets = STYLE_BRACKETS.equals(style)
                        || STYLE_PULSING_BRACKETS.equals(style);

        float drawAlpha = alpha;
        if (pulsing) {
            // 1.5 Hz pulse, range 0.1 – 1.0 of alpha
            float pulse = (float) (Math.sin(time * Math.PI * 1.5) * 0.5 + 0.5);
            drawAlpha = alpha * (0.1f + 0.9f * pulse);
        }

        glBegin();
        try {
            float radius = (float) Math.sqrt(dim.halfFwd * dim.halfFwd
                                           + dim.halfSide * dim.halfSide);
            if (brackets) {
                drawBrackets(loc,
                             dim.halfFwd  * BRACKET_OFFSET_MULT,
                             dim.halfSide * BRACKET_OFFSET_MULT,
                             ship.getFacing(),
                             color, drawAlpha, lineWidth);
            } else {
                drawCircle(loc, radius, color, drawAlpha, lineWidth);
            }
            if (directionEnabled && ship != null) {
                Vector2f vel = ship.getVelocity();
                float speed = vel.length();
                if (speed > 0.1f) {
                    float moveDeg = (float) Math.toDegrees(Math.atan2(vel.y, vel.x));
                    if (moveDeg < 0f) moveDeg += 360f;
                    float arrowAlpha = drawAlpha;
                    if (speed < 50f) {
                        arrowAlpha *= (speed / 50f);
                    }
                    if (arrowAlpha > 0.005f) {
                        drawDirectionArrow(loc, radius, arrowAlpha, lineWidth, color, moveDeg);
                    }
                }
            }
        } finally {
            glEnd();
        }
    }

    // -------------------------------------------------------------------------
    // Sizing — sprite-aware rectangle (halfFwd × halfSide).
    //
    // Sprites in Starsector have the nose at image-top, so spriteHeight is the
    // bow–stern length and spriteWidth is the port–starboard beam.  Using these
    // gives the indicator the real aspect of the hull — long ships get long
    // brackets, square ones get square brackets.
    //
    // Modular-ship safety net: some hulls (and most modular stations) have a
    // tiny or fully hidden "command" sprite that doesn't represent the actual
    // mass the player is flying.  When the sprite's longest axis is much
    // smaller than the collision radius, fall back to collisionRadius so the
    // indicator wraps the real ship instead of a one-pixel bridge.
    //
    // Shield cap keeps both extents inside the shield bubble (8 % gap) so the
    // ring/brackets don't visually merge with the shield outline when raised.
    // Phase ships have no shield and are unaffected.

    private static class Dims {
        float halfFwd;   // half-length along nose–stern axis (local +X = forward)
        float halfSide;  // half-width  across port–starboard  (local +Y = port)
    }

    private Dims calcDims(ShipAPI ship) {
        Dims d = new Dims();

        SpriteAPI sprite = ship.getSpriteAPI();
        float     collR  = ship.getCollisionRadius();

        if (sprite != null && sprite.getWidth() > 0f && sprite.getHeight() > 0f) {
            d.halfFwd  = sprite.getHeight() * 0.5f;
            d.halfSide = sprite.getWidth()  * 0.5f;

            // Hidden / decoy sprite fallback (modular ships, big stations).
            float longest = Math.max(d.halfFwd, d.halfSide);
            if (longest < collR * 0.50f) {
                d.halfFwd  = collR;
                d.halfSide = collR;
            }
        } else {
            d.halfFwd  = collR;
            d.halfSide = collR;
        }

        ShieldAPI shield = ship.getShield();
        if (shield != null && shield.getRadius() > 0f) {
            float cap  = shield.getRadius() * 0.92f;
            float diag = (float) Math.sqrt(d.halfFwd * d.halfFwd + d.halfSide * d.halfSide);
            if (diag > cap) {
                float scale = cap / diag;
                d.halfFwd  *= scale;
                d.halfSide *= scale;
            }
        }

        d.halfFwd  *= sizeMult;
        d.halfSide *= sizeMult;
        return d;
    }

    // -------------------------------------------------------------------------
    // GL state

    private void glBegin() {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(
                viewport.getLLX(), viewport.getLLX() + viewport.getVisibleWidth(),
                viewport.getLLY(), viewport.getLLY() + viewport.getVisibleHeight(),
                -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void glEnd() {
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glPopAttrib();
    }

    private void setColor(Color c, float a) {
        GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, clamp(a, 0f, 1f));
    }

    // -------------------------------------------------------------------------
    // Shapes

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

    /**
     * Smooth circle ring.
     *
     * Segment count adapts to the projected screen circumference so the ring
     * looks smooth whether the camera is zoomed in tight or pulled way back.
     * Target: ~1.5 screen pixels per segment chord.  Clamped to [64, 512].
     */
    private void drawCircle(Vector2f center, float radius, Color c, float a, float lw) {
        GL11.glLineWidth(lw);
        setColor(c, a);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < SG_64; i++) {
            GL11.glVertex2f(
                    center.x + radius * COS_64[i],
                    center.y + radius * SIN_64[i]);
        }
        GL11.glEnd();
    }

    /**
     * Four L-shaped corner brackets on a rectangle of size 2*halfFwd × 2*halfSide,
     * oriented along the ship's facing direction (so the rectangle's long axis
     * follows the hull instead of being world-axis-aligned).
     *
     * Each arm spans 30 % of its corresponding half-extent, so arms stay
     * proportional whether the ship is long-and-thin or roughly square.
     */
    private void drawBrackets(Vector2f center, float halfFwd, float halfSide,
                              float facingDeg, Color c, float a, float lw) {
        GL11.glLineWidth(lw);
        setColor(c, a);

        float armFwd  = halfFwd  * 0.30f;
        float armSide = halfSide * 0.30f;

        double rad = Math.toRadians(facingDeg);
        float  cos = (float) Math.cos(rad);
        float  sin = (float) Math.sin(rad);

        // Local frame: +X = forward (toward nose), +Y = port (left from nose POV).
        // Corner order: nose-port, nose-stbd, stern-stbd, stern-port.
        float[][] corners = {
            { halfFwd,  halfSide},
            { halfFwd, -halfSide},
            {-halfFwd, -halfSide},
            {-halfFwd,  halfSide},
        };
        // Inward direction along the forward axis at each corner (toward center).
        float[] fwdDir  = {-1f, -1f, +1f, +1f};
        // Inward direction along the side axis at each corner.
        float[] sideDir = {-1f, +1f, +1f, -1f};

        GL11.glBegin(GL11.GL_LINES);
        for (int i = 0; i < 4; i++) {
            float lx = corners[i][0];
            float ly = corners[i][1];

            // Arm tip along the forward axis.
            float fx = lx + fwdDir[i] * armFwd;
            float fy = ly;

            // Arm tip along the side axis.
            float sx = lx;
            float sy = ly + sideDir[i] * armSide;

            // Rotate local-space points by facing, translate to ship center.
            float cx  = center.x + lx * cos - ly * sin;
            float cy  = center.y + lx * sin + ly * cos;
            float fwx = center.x + fx * cos - fy * sin;
            float fwy = center.y + fx * sin + fy * cos;
            float swx = center.x + sx * cos - sy * sin;
            float swy = center.y + sx * sin + sy * cos;

            GL11.glVertex2f(cx, cy);
            GL11.glVertex2f(fwx, fwy);
            GL11.glVertex2f(cx, cy);
            GL11.glVertex2f(swx, swy);
        }
        GL11.glEnd();
    }

    private Vector2f getVisualCenter(ShipAPI ship) {
        if (ship == null) return new Vector2f();
        Vector2f center = ship.getShieldCenterEvenIfNoShield();
        if (center == null) return ship.getLocation();
        return center;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
