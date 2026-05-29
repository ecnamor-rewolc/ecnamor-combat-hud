package ecnamor.hud.boundary;


import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.EnumSet;

public class BoundaryRenderer extends BaseCombatLayeredRenderingPlugin {

    private static final Color BOUNDARY_COLOR      = new Color(255, 40, 40);
    private static final float BOUNDARY_LINE_WIDTH = 4f;
    private static final float BOUNDARY_ALPHA      = 0.90f;

    private static final float STRIPE_WIDTH        = 40f;
    private static final float STRIPE_GAP          = 25f;
    private static final float OUTSIDE_DEPTH       = 600f;
    private static final float OUTSIDE_ALPHA       = 0.35f;

    private static final Color RETREAT_COLOR        = new Color(255, 220, 30);
    private static final float RETREAT_LINE_WIDTH   = 3f;
    private static final float RETREAT_BASE_ALPHA   = 0.05f;
    private static final float RETREAT_PEAK_ALPHA   = 0.65f;
    private static final float RETREAT_FACING_POWER = 1.5f;
    private static final float RETREAT_DEPTH_OUT    = 400f;

    private final CombatEngineAPI engine;

    BoundaryRenderer(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public float getRenderRadius() { return 9.9999999E14f; }

    @Override
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        return EnumSet.of(CombatEngineLayers.BELOW_SHIPS_LAYER);
    }

    @Override
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        if (engine == null || layer != CombatEngineLayers.BELOW_SHIPS_LAYER) return;
        if (engine.getCombatUI() != null && engine.getCombatUI().isShowingCommandUI()) return;

        float halfW = engine.getMapWidth()  * 0.5f;
        float halfH = engine.getMapHeight() * 0.5f;
        float br    = BOUNDARY_COLOR.getRed()   / 255f;
        float bg    = BOUNDARY_COLOR.getGreen() / 255f;
        float bb    = BOUNDARY_COLOR.getBlue()  / 255f;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPushMatrix(); GL11.glLoadIdentity();
        GL11.glOrtho(viewport.getLLX(), viewport.getLLX() + viewport.getVisibleWidth(),
                     viewport.getLLY(), viewport.getLLY() + viewport.getVisibleHeight(), -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix(); GL11.glLoadIdentity();
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_LINE_SMOOTH); GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);

            GL11.glLineWidth(BOUNDARY_LINE_WIDTH);
            GL11.glColor4f(br, bg, bb, BOUNDARY_ALPHA);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(-halfW, -halfH);
            GL11.glVertex2f( halfW, -halfH);
            GL11.glVertex2f( halfW,  halfH);
            GL11.glVertex2f(-halfW,  halfH);
            GL11.glEnd();

            GL11.glBegin(GL11.GL_QUADS);
            drawBoundaryStripes(halfW, halfH, OUTSIDE_DEPTH, OUTSIDE_ALPHA, br, bg, bb, false);
            GL11.glEnd();

            float facingMult = computeRetreatFacingMult();
            if (facingMult > 0.001f) {
                float yr = RETREAT_COLOR.getRed()   / 255f;
                float yg = RETREAT_COLOR.getGreen() / 255f;
                float yb = RETREAT_COLOR.getBlue()  / 255f;
                float yAlphaBoundary = RETREAT_BASE_ALPHA + (RETREAT_PEAK_ALPHA - RETREAT_BASE_ALPHA) * facingMult;
                float yAlphaStripeO  = yAlphaBoundary * 0.40f;

                GL11.glLineWidth(RETREAT_LINE_WIDTH);
                GL11.glColor4f(yr, yg, yb, yAlphaBoundary);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glVertex2f(-halfW, -halfH);
                GL11.glVertex2f( halfW, -halfH);
                GL11.glEnd();

                GL11.glBegin(GL11.GL_QUADS);
                drawBoundaryStripes(halfW, halfH, RETREAT_DEPTH_OUT, yAlphaStripeO, yr, yg, yb, true);
                GL11.glEnd();
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private float computeRetreatFacingMult() {
        ShipAPI p = engine.getPlayerShip();
        if (p == null || !p.isAlive() || p.isHulk()) return 0f;
        float facingY = (float) Math.sin(Math.toRadians(p.getFacing()));
        float alignment = Math.max(0f, -facingY);
        return (float) Math.pow(alignment, RETREAT_FACING_POWER);
    }

    private static void drawBoundaryStripes(float halfW, float halfH, float outsideDepth, float maxAlpha,
                                            float r, float g, float b, boolean isRetreatOnly) {
        if (Float.isNaN(halfW) || Float.isNaN(halfH) || Float.isNaN(outsideDepth) || Float.isNaN(maxAlpha)) return;

        float stride = STRIPE_WIDTH + STRIPE_GAP;
        float dRange = halfW + halfH + outsideDepth + stride + 1000f;

        float yMinOut = -halfH - outsideDepth;
        float yMaxOut = isRetreatOnly ? -halfH : (halfH + outsideDepth);
        float xMinOut = -halfW - outsideDepth;
        float xMaxOut = halfW + outsideDepth;

        for (float d = -dRange; d < dRange; d += stride) {
            float stripeXMin = d + yMinOut;
            float stripeXMax = d + STRIPE_WIDTH + yMaxOut;
            if (stripeXMax < xMinOut || stripeXMin > xMaxOut) continue;

            float[] temp = new float[16];
            int count = 0;

            temp[count++] = yMinOut;
            temp[count++] = yMaxOut;
            if (!isRetreatOnly) {
                temp[count++] = -halfH;
                temp[count++] = halfH;
            }

            float y;
            y = xMinOut - d;
            if (y > yMinOut && y < yMaxOut) temp[count++] = y;
            y = xMaxOut - d;
            if (y > yMinOut && y < yMaxOut) temp[count++] = y;

            y = xMinOut - d - STRIPE_WIDTH;
            if (y > yMinOut && y < yMaxOut) temp[count++] = y;
            y = xMaxOut - d - STRIPE_WIDTH;
            if (y > yMinOut && y < yMaxOut) temp[count++] = y;

            if (!isRetreatOnly) {
                y = -halfW - d;
                if (y > yMinOut && y < yMaxOut) temp[count++] = y;
                y = halfW - d;
                if (y > yMinOut && y < yMaxOut) temp[count++] = y;

                y = -halfW - d - STRIPE_WIDTH;
                if (y > yMinOut && y < yMaxOut) temp[count++] = y;
                y = halfW - d - STRIPE_WIDTH;
                if (y > yMinOut && y < yMaxOut) temp[count++] = y;
            }

            for (int i = 0; i < count - 1; i++) {
                for (int j = i + 1; j < count; j++) {
                    if (temp[i] > temp[j]) {
                        float t = temp[i];
                        temp[i] = temp[j];
                        temp[j] = t;
                    }
                }
            }

            int uniqueCount = 0;
            for (int i = 0; i < count; i++) {
                if (uniqueCount == 0 || temp[i] - temp[uniqueCount - 1] > 0.001f) {
                    temp[uniqueCount++] = temp[i];
                }
            }

            for (int i = 0; i < uniqueCount - 1; i++) {
                float y1 = temp[i];
                float y2 = temp[i + 1];
                float yMid = (y1 + y2) * 0.5f;

                if (isRetreatOnly || yMid <= -halfH || yMid >= halfH) {
                    drawStripeSegment(y1, y2, xMinOut, xMaxOut, d, halfW, halfH, outsideDepth, maxAlpha, r, g, b);
                } else {
                    drawStripeSegment(y1, y2, xMinOut, -halfW, d, halfW, halfH, outsideDepth, maxAlpha, r, g, b);
                    drawStripeSegment(y1, y2, halfW, xMaxOut, d, halfW, halfH, outsideDepth, maxAlpha, r, g, b);
                }
            }
        }
    }

    private static void drawStripeSegment(float y1, float y2, float xLeftLimit, float xRightLimit, float d,
                                          float halfW, float halfH, float outsideDepth, float maxAlpha,
                                          float r, float g, float b) {
        float xStart1 = Math.max(xLeftLimit, d + y1);
        float xEnd1 = Math.min(xRightLimit, d + STRIPE_WIDTH + y1);
        float xStart2 = Math.max(xLeftLimit, d + y2);
        float xEnd2 = Math.min(xRightLimit, d + STRIPE_WIDTH + y2);

        if (xStart1 >= xEnd1 && xStart2 >= xEnd2) return;

        float a1 = calculateAlpha(xStart1, y1, halfW, halfH, outsideDepth, maxAlpha);
        GL11.glColor4f(r, g, b, a1);
        GL11.glVertex2f(xStart1, y1);

        float a2 = calculateAlpha(xEnd1, y1, halfW, halfH, outsideDepth, maxAlpha);
        GL11.glColor4f(r, g, b, a2);
        GL11.glVertex2f(xEnd1, y1);

        float a3 = calculateAlpha(xEnd2, y2, halfW, halfH, outsideDepth, maxAlpha);
        GL11.glColor4f(r, g, b, a3);
        GL11.glVertex2f(xEnd2, y2);

        float a4 = calculateAlpha(xStart2, y2, halfW, halfH, outsideDepth, maxAlpha);
        GL11.glColor4f(r, g, b, a4);
        GL11.glVertex2f(xStart2, y2);
    }

    private static float calculateAlpha(float x, float y, float halfW, float halfH, float outsideDepth, float maxAlpha) {
        float dx = Math.max(0f, Math.max(-halfW - x, x - halfW));
        float dy = Math.max(0f, Math.max(-halfH - y, y - halfH));
        float dist = Math.max(dx, dy);
        float alpha = maxAlpha * (1f - dist / outsideDepth);
        return Math.max(0f, Math.min(maxAlpha, alpha));
    }
}
