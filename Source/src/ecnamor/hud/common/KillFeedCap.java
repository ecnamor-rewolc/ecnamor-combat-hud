package ecnamor.hud.common;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import org.magiclib.ReflectionUtils;
import org.magiclib.ReflectionUtils.ReflectedField;

import java.util.List;

/**
 * Caps vanilla combat killfeed/message list to a max number of entries by removing
 * oldest items from the underlying {@code LinkedList<o>} inside
 * {@code com.fs.starfarer.class.C} (= CombatState.messageWidget).
 * <p>
 * Compatible with Combat Chatter — chatter messages flow through the same
 * {@code messageWidget.addMessage} path and will be capped together with kill notices.
 * <p>
 * Uses MagicLib's {@link ReflectionUtils} to bypass Starsector's script reflection
 * sandbox. References cached after first successful lookup.
 */
public final class KillFeedCap {

    private CombatEngineAPI cachedEngine;
    private Object          cachedWidget;
    private Object          cachedList;

    public KillFeedCap() {}

    /**
     * Trims vanilla killfeed to {@code maxLines} entries (oldest dropped).
     * <ul>
     *   <li>{@code maxLines < 0} → no-op (vanilla unlimited).</li>
     *   <li>{@code maxLines == 0} → fully empties the list (killfeed hidden).</li>
     *   <li>{@code maxLines > 0} → keeps only the {@code maxLines} most recent.</li>
     * </ul>
     */
    public void cap(CombatEngineAPI engine, int maxLines) {
        if (engine == null || maxLines < 0) return;
        try {
            Object widget = getMessageWidget(engine);
            if (widget == null) return;
            Object listObj = getMessageList(widget);
            if (!(listObj instanceof List)) return;
            List<?> list = (List<?>) listObj;
            // Newer messages are added at index 0, so older entries live at the tail.
            // Drop from the tail until size matches cap.
            while (list.size() > maxLines) {
                list.remove(list.size() - 1);
            }
        } catch (Throwable ignored) {
            // Cached refs may be stale (rare). Drop them so next call refetches.
            cachedEngine = null;
            cachedWidget = null;
            cachedList   = null;
        }
    }

    private Object getMessageWidget(CombatEngineAPI engine) {
        if (engine == cachedEngine && cachedWidget != null) return cachedWidget;
        try {
            ReflectedField csField =
                    ReflectionUtils.INSTANCE.findFieldWithMethodName(engine, "isHideHud");
            if (csField == null) return null;
            Object combatState = csField.get(engine);
            if (combatState == null) return null;
            // CombatState has a cleartext field "messageWidget" of type
            // com.fs.starfarer.class.C (verified via CFR decompile).
            Object widget = ReflectionUtils.get("messageWidget", combatState);
            if (widget != null) {
                cachedEngine = engine;
                cachedWidget = widget;
            }
            return widget;
        } catch (Throwable t) {
            return null;
        }
    }

    private Object getMessageList(Object widget) {
        if (cachedList != null) return cachedList;
        try {
            List<ReflectedField> listFields =
                    ReflectionUtils.INSTANCE.findFieldsOfType(widget, List.class);
            if (listFields.isEmpty()) return null;
            // Only one List<?> field exists on the widget — the message LinkedList.
            cachedList = listFields.get(0).get(widget);
            return cachedList;
        } catch (Throwable t) {
            return null;
        }
    }
}
