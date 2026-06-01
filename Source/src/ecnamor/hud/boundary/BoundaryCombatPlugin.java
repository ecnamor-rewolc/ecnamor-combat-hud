package ecnamor.hud.boundary;

/*
 * ──────────────────────────────────────────────────────────────────────────────
 *  Off-screen flagship marker. A bright line slides smoothly along the screen
 *  perimeter pointing toward the player ship when it leaves the visible area.
 *  Three-pass rendering (bloom → mid → core) for a glow effect. The center of
 *  the bar fades toward near-white for an emissive bloom feel.
 *
 *  A rotating semi-arc indicator near (but offset from) screen center shows
 *  when the player ship is off-screen and rotates to point toward it. Fades
 *  out when the ship re-enters the viewport.
 *
 *  Both elements are configurable via LunaLib (color presets, opacity).
 *
 *  Companion class BoundaryRenderer draws the actual battle-map boundary
 *  line + diagonal hazard stripes + yellow retreat zone on the map plane.
 *
 *  -- TUNING --
 *    Bar fade zone      : FADE_ZONE      (px from edge where bar starts appearing)
 *    Bar length         : BAR_HALF_LEN  (half-length along the perimeter)
 *    Bar resolution     : BAR_STEPS
 *    Pass widths/alphas : BAR_CORE_*, BAR_MID_*, BAR_BLOOM_*
 *    Arc indicator      : ARC_RADIUS, ARC_SPAN_DEG, ARC_SEGS
 *    Marker offset      : MARKER_OFFSET_X, MARKER_OFFSET_Y
 * ──────────────────────────────────────────────────────────────────────────────
 */

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;

public class BoundaryCombatPlugin extends BaseEveryFrameCombatPlugin {

    private static final String MOD_ID = "ecnamor_combat_hud";

    // ── Vanilla palette ───────────────────────────────────────────────────────
    private static final Color VANILLA_GREEN  = new Color( 70, 255, 110);
    private static final Color VANILLA_BLUE   = new Color( 70, 200, 255);
    private static final Color VANILLA_YELLOW = new Color(255, 220,  70);

    private static final String PRESET_GREEN  = "Vanilla Green";
    private static final String PRESET_BLUE   = "Vanilla Blue";
    private static final String PRESET_YELLOW = "Vanilla Yellow";

    // ── Edge bar tuning ───────────────────────────────────────────────────────
    /** Px from edge where bar starts fading in (ship still on screen). */
    private static final float FADE_ZONE       = 150f;
    /** Half-length of the bar along the perimeter. */
    private static final float BAR_HALF_LEN    = 90f;
    /** Line-strip segments used to trace the bar. */
    private static final int   BAR_STEPS       = 32;

    private static final float BAR_CORE_WIDTH  =  6.0f;
    private static final float BAR_CORE_ALPHA  =  1.00f;
    private static final float BAR_MID_WIDTH   = 12.0f;
    private static final float BAR_MID_ALPHA   =  0.55f;
    private static final float BAR_BLOOM_WIDTH = 24.0f;
    private static final float BAR_BLOOM_ALPHA =  0.20f;

    // ── Arc indicator tuning ──────────────────────────────────────────────────
    /** Semi-arc radius in screen pixels. */
    private static final float ARC_RADIUS      = 80f;
    /** Total arc span in degrees (centered on direction toward ship). */
    private static final float ARC_SPAN_DEG    = 90f;
    /** Number of segments for the arc strip. */
    private static final int   ARC_SEGS        = 32;
    /** How far the arrowhead tip sits beyond the arc radius. */
    private static final float ARROW_TIP_EXTRA = 18f;
    /** How far the arrowhead base sits beyond the arc radius (arrow fully in front). */
    private static final float ARROW_BASE_EXTRA = 3f;
    /** Arrowhead half-base width. */
    private static final float ARROW_HALF_W    = 8f;
    /** Offset of the marker from screen center (+X = right, +Y = up). */
    private static final float MARKER_OFFSET_X = 0f;
    private static final float MARKER_OFFSET_Y = 0f;

    // ── Runtime settings ──────────────────────────────────────────────────────
    private float barR = VANILLA_BLUE.getRed()   / 255f;
    private float barG = VANILLA_BLUE.getGreen() / 255f;
    private float barB = VANILLA_BLUE.getBlue()  / 255f;
    private float barAlphaMult = 1.0f;

    private float markerR = VANILLA_BLUE.getRed()   / 255f;
    private float markerG = VANILLA_BLUE.getGreen() / 255f;
    private float markerB = VANILLA_BLUE.getBlue()  / 255f;
    private float markerAlphaMult = 0.85f;

    private CombatEngineAPI  engine;
    private BoundaryRenderer renderer;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine   = engine;
        this.renderer = new BoundaryRenderer(engine);
        engine.addLayeredRenderingPlugin(renderer);
        loadSettings();
    }

    private void loadSettings() {
        if (!Global.getSettings().getModManager().isModEnabled("lunalib")) return;
        try {
            Color bc = resolveColor(
                    lunalib.lunaSettings.LunaSettings.getString(MOD_ID, "boundary_bar_color_preset"),
                    "boundary_bar_color", VANILLA_BLUE);
            barR = bc.getRed() / 255f; barG = bc.getGreen() / 255f; barB = bc.getBlue() / 255f;
            Double d = lunalib.lunaSettings.LunaSettings.getDouble(MOD_ID, "boundary_bar_alpha");
            if (d != null) barAlphaMult = clamp(d.floatValue(), 0f, 1f);

            Color mc = resolveColor(
                    lunalib.lunaSettings.LunaSettings.getString(MOD_ID, "boundary_marker_color_preset"),
                    "boundary_marker_color", VANILLA_BLUE);
            markerR = mc.getRed() / 255f; markerG = mc.getGreen() / 255f; markerB = mc.getBlue() / 255f;
            d = lunalib.lunaSettings.LunaSettings.getDouble(MOD_ID, "boundary_marker_alpha");
            if (d != null) markerAlphaMult = clamp(d.floatValue(), 0f, 1f);

        } catch (Exception ignored) {}
    }

    private Color resolveColor(String preset, String customKey, Color def) {
        if (PRESET_GREEN .equals(preset)) return VANILLA_GREEN;
        if (PRESET_BLUE  .equals(preset)) return VANILLA_BLUE;
        if (PRESET_YELLOW.equals(preset)) return VANILLA_YELLOW;
        try {
            Color c = lunalib.lunaSettings.LunaSettings.getColor(MOD_ID, customKey);
            if (c != null) return c;
        } catch (Exception ignored) {}
        return def;
    }

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        if (engine == null) return;
        if (engine.getCombatUI() != null && engine.getCombatUI().isShowingCommandUI()) return;
        if (!engine.isUIShowingHUD()) return;

        ShipAPI player = engine.getPlayerShip();
        if (player == null || !player.isAlive() || player.isHulk()) return;

        float uiscale = Math.max(1f, Global.getSettings().getScreenScaleMult());
        float sw = Global.getSettings().getScreenWidthPixels() / uiscale;
        float sh = Global.getSettings().getScreenHeightPixels() / uiscale;

        Vector2f loc = player.getLocation();
        float sx = viewport.convertWorldXtoScreenX(loc.x);
        float sy = viewport.convertWorldYtoScreenY(loc.y);

        // Distance from nearest screen edge (positive = inside, negative = outside)
        float marginX  = Math.min(sx, sw - sx);
        float marginY  = Math.min(sy, sh - sy);
        float edgeDist = Math.min(marginX, marginY);

        // Bar: fades in as ship approaches or leaves screen boundary
        float barAlpha = clamp(1f - edgeDist / FADE_ZONE, 0f, 1f) * barAlphaMult;

        // Arc indicator: only active when ship is off-screen, fades in quickly
        float arcAlpha = (edgeDist < 0f)
                ? clamp(-edgeDist / 60f, 0f, 1f) * markerAlphaMult
                : 0f;

        if (barAlpha <= 0.01f && arcAlpha <= 0.01f) return;

        // Direction from screen centre toward ship (or its projection)
        float cx = sw * 0.5f, cy = sh * 0.5f;
        float dx = sx - cx, dy = sy - cy;
        if (Math.abs(dx) < 0.5f && Math.abs(dy) < 0.5f) return;

        // Perimeter intersection for the bar
        float tX = (dx != 0f) ? Math.abs(((dx > 0f ? sw : 0f) - cx) / dx) : Float.MAX_VALUE;
        float tY = (dy != 0f) ? Math.abs(((dy > 0f ? sh : 0f) - cy) / dy) : Float.MAX_VALUE;
        float t  = Math.min(tX, tY);
        if (t <= 0f) return;

        float ix = clamp(cx + dx * t, 0f, sw);
        float iy = clamp(cy + dy * t, 0f, sh);

        float perimLen = 2f * (sw + sh);
        float periPos  = perimPos(ix, iy, sw, sh);

        glBegin(sw, sh);
        try {
            if (barAlpha > 0.01f) {
                drawPerimBar(periPos - BAR_HALF_LEN, periPos + BAR_HALF_LEN,
                        sw, sh, perimLen, barAlpha, barR, barG, barB);
            }
            if (arcAlpha > 0.01f) {
                drawArcMarker(cx + MARKER_OFFSET_X, cy + MARKER_OFFSET_Y,
                        dx, dy, arcAlpha, markerR, markerG, markerB);
            }
        } finally {
            glEnd();
        }
    }

    /**
     * Three-pass bar along the screen perimeter (bloom → mid → core).
     * Each vertex color interpolates toward near-white at the bar center
     * to simulate a glow / bloom highlight on the brightest point.
     */
    private static void drawPerimBar(float t0, float t1, float sw, float sh,
                                     float perimLen, float alpha,
                                     float r, float g, float b) {
        float[][] passes = {
            {BAR_BLOOM_WIDTH, BAR_BLOOM_ALPHA},
            {BAR_MID_WIDTH,   BAR_MID_ALPHA  },
            {BAR_CORE_WIDTH,  BAR_CORE_ALPHA },
        };
        for (float[] pass : passes) {
            GL11.glLineWidth(pass[0]);
            GL11.glBegin(GL11.GL_LINE_STRIP);
            for (int i = 0; i <= BAR_STEPS; i++) {
                float frac = (float) i / BAR_STEPS;
                // glow: 0 at ends, 1 at center (frac=0.5)
                float glow  = 1f - Math.abs(frac * 2f - 1f);
                float glow2 = glow * glow;
                // Blend color toward near-white at center (85% of the way)
                float vr = r + (1f - r) * glow2 * 0.85f;
                float vg = g + (1f - g) * glow2 * 0.85f;
                float vb = b + (1f - b) * glow2 * 0.85f;
                GL11.glColor4f(vr, vg, vb, pass[1] * alpha);
                float[] p = perimPoint(t0 + (t1 - t0) * frac, sw, sh, perimLen);
                GL11.glVertex2f(p[0], p[1]);
            }
            GL11.glEnd();
        }
    }

    /**
     * Rotating semi-arc indicator. Centered at (mx, my) — offset from screen center.
     * The arc midpoint rotates to point in direction (dx, dy) toward the off-screen ship.
     * A filled triangle arrowhead sits at the arc midpoint pointing radially outward.
     */
    private static void drawArcMarker(float mx, float my, float dx, float dy,
                                      float alpha, float r, float g, float b) {
        float dirLen = (float) Math.sqrt(dx * dx + dy * dy);
        if (dirLen < 0.5f) return;

        float dirAngleDeg = (float) Math.toDegrees(Math.atan2(dy, dx));
        float arcStart    = dirAngleDeg - ARC_SPAN_DEG * 0.5f;

        // ── Arc (single pass, per-vertex edge fade) ───────────────────────────
        GL11.glLineWidth(2f);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        for (int i = 0; i <= ARC_SEGS; i++) {
            float edgeFade = (float) Math.sin(Math.PI * i / ARC_SEGS);
            double a = Math.toRadians(arcStart + ARC_SPAN_DEG * i / ARC_SEGS);
            GL11.glColor4f(r, g, b, 0.90f * alpha * edgeFade);
            GL11.glVertex2f(mx + ARC_RADIUS * (float) Math.cos(a),
                            my + ARC_RADIUS * (float) Math.sin(a));
        }
        GL11.glEnd();

        // ── Chevron (unfilled arrowhead) at arc midpoint ──────────────────────
        double midRad = Math.toRadians(dirAngleDeg);
        float  cosM   = (float) Math.cos(midRad);
        float  sinM   = (float) Math.sin(midRad);

        float tipX  = mx + (ARC_RADIUS + ARROW_TIP_EXTRA) * cosM;
        float tipY  = my + (ARC_RADIUS + ARROW_TIP_EXTRA) * sinM;

        float d_parallel = 20f;
        float d_perpendicular = 16f;

        float lx = tipX - d_parallel * cosM - d_perpendicular * sinM;
        float ly = tipY - d_parallel * sinM + d_perpendicular * cosM;
        float rx = tipX - d_parallel * cosM + d_perpendicular * sinM;
        float ry = tipY - d_parallel * sinM - d_perpendicular * cosM;

        GL11.glLineWidth(2f);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        GL11.glColor4f(r, g, b, 0.90f * alpha);
        GL11.glVertex2f(lx, ly);
        GL11.glVertex2f(tipX, tipY);
        GL11.glVertex2f(rx, ry);
        GL11.glEnd();
    }

    // ── Perimeter math ────────────────────────────────────────────────────────

    /**
     * Converts an (x,y) point on the screen boundary to a perimeter distance t.
     * Runs clockwise: bottom → right → top → left.
     */
    private static float perimPos(float ix, float iy, float sw, float sh) {
        float eps = 2f;
        if (iy < eps)      return ix;
        if (ix > sw - eps) return sw + iy;
        if (iy > sh - eps) return sw + sh + (sw - ix);
        return 2f * sw + sh + (sh - iy);
    }

    /** Converts a perimeter distance t to an (x,y) screen coordinate. */
    private static float[] perimPoint(float t, float sw, float sh, float perimLen) {
        t = ((t % perimLen) + perimLen) % perimLen;
        if (t < sw) return new float[]{t,      0f  };
        t -= sw;
        if (t < sh) return new float[]{sw,     t   };
        t -= sh;
        if (t < sw) return new float[]{sw - t, sh  };
        t -= sw;
        return             new float[]{0f,     sh - t};
    }

    // ── GL state ──────────────────────────────────────────────────────────────

    private static void glBegin(float sw, float sh) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glOrtho(0, sw, 0, sh, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void glEnd() {
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPopMatrix();
        GL11.glPopAttrib();
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
