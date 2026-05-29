package ecnamor.hud.common;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.ShipAPI;
import org.magiclib.ReflectionUtils;
import org.magiclib.ReflectionUtils.ReflectedField;
import java.util.List;
import java.util.Iterator;

public final class Inspector {

    private static Class<?> indClass = null;
    private static Class<?> faderClass = null;
    private static ReflectedField cachedFFIndField = null;
    private static ReflectedField cachedFaderField = null;
    private static java.lang.reflect.Method getListMethod = null;

    static {
        try {
            ClassLoader loader = Global.class.getClassLoader();
            indClass = Class.forName("com.fs.starfarer.renderers.OOoO", false, loader);
            faderClass = Class.forName("com.fs.graphics.util.Fader", false, loader);
        } catch (Throwable ignored) {}
    }

    private static List<?> getLayerList(Object layeredRenderer, Enum<?> layer) {
        try {
            if (getListMethod == null) {
                List<?> methods = ReflectionUtils.INSTANCE.getMethodsOfName("getList", layeredRenderer);
                for (Object mObj : methods) {
                    java.lang.reflect.Method m = (java.lang.reflect.Method) mObj;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 1 && params[0].isAssignableFrom(layer.getClass())) {
                        getListMethod = m;
                        getListMethod.setAccessible(true);
                        break;
                    }
                }
            }
            if (getListMethod != null) {
                return (List<?>) ReflectionUtils.INSTANCE.rawInvoke(getListMethod, layeredRenderer, new Object[] { layer });
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    public static void inspect(CombatEngineAPI engine) {
        if (engine == null) return;
        Object ui = engine.getCombatUI();
        if (ui == null || ui.getClass().getName().contains("TitleScreenState")) {
            return;
        }

        try {
            List<ShipAPI> ships = engine.getShips();
            if (ships != null && !ships.isEmpty()) {
                if (indClass != null && cachedFFIndField == null) {
                    List<ReflectedField> fs = ReflectionUtils.INSTANCE.findFieldsOfType(ships.get(0), indClass);
                    if (!fs.isEmpty()) {
                        cachedFFIndField = fs.get(0);
                    }
                }

                for (int i = 0, n = ships.size(); i < n; i++) {
                    ShipAPI ship = ships.get(i);
                    if (ship == null) continue;

                    ship.setForceHideFFOverlay(true);

                    if (cachedFFIndField != null) {
                        try {
                            Object ffInd = cachedFFIndField.get(ship);
                            if (ffInd != null) {
                                if (faderClass != null) {
                                    if (cachedFaderField == null) {
                                        List<ReflectedField> ff = ReflectionUtils.INSTANCE.findFieldsOfType(ffInd, faderClass);
                                        if (!ff.isEmpty()) {
                                            cachedFaderField = ff.get(0);
                                        }
                                    }
                                    if (cachedFaderField != null) {
                                        Object fader = cachedFaderField.get(ffInd);
                                        if (fader != null) {
                                            ReflectionUtils.INSTANCE.invoke("forceOut", fader, new Object[0], false);
                                            ReflectionUtils.set("currBrightness", fader, 0f);
                                            try {
                                                Class<?> stateEnum = Class.forName("com.fs.graphics.util.Fader$State", false, Global.class.getClassLoader());
                                                ReflectionUtils.set("state", fader, Enum.valueOf((Class<Enum>) stateEnum, "OUT"));
                                            } catch (Throwable ignored) {}
                                        }
                                    }
                                }

                                List<ReflectedField> colors = ReflectionUtils.INSTANCE.findFieldsOfType(ffInd, java.awt.Color.class);
                                for (int j = 0, k = colors.size(); j < k; j++) {
                                    colors.get(j).set(ffInd, new java.awt.Color(0, 0, 0, 0));
                                }

                                List<ReflectedField> floats = ReflectionUtils.INSTANCE.findFieldsOfType(ffInd, float.class);
                                for (int j = 0, k = floats.size(); j < k; j++) {
                                    floats.get(j).set(ffInd, 0f);
                                }
                            }
                        } catch (Throwable ignored) {
                            cachedFFIndField = null;
                            cachedFaderField = null;
                        }
                    }
                }
            }

            Object layeredRenderer = ReflectionUtils.get("new", engine);
            if (layeredRenderer != null) {
                List<?> ffIndicatorsList = getLayerList(layeredRenderer, CombatEngineLayers.FF_INDICATORS_LAYER);
                if (ffIndicatorsList != null) {
                    Iterator<?> iterator = ffIndicatorsList.iterator();
                    while (iterator.hasNext()) {
                        Object obj = iterator.next();
                        if (obj instanceof ShipAPI) {
                            iterator.remove();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}
