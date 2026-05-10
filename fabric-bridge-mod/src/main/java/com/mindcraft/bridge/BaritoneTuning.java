package com.mindcraft.bridge;

import java.lang.reflect.Field;

/**
 * Applies opinionated Baritone settings for more human-like movement.
 * Called once after the first client tick with a loaded world.
 *
 * All accesses are reflective because the bridge mod has no compile-time
 * dependency on Baritone. Missing/renamed settings log a warning and skip.
 */
public class BaritoneTuning {

    private BaritoneTuning() {}

    public static void applyOpinionatedDefaults() {
        Object settings;
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            settings = apiClass.getMethod("getSettings").invoke(null);
        } catch (Throwable t) {
            MindcraftBridgeMod.LOGGER.warn("Baritone not loaded; skipping tuning: {}", t.getMessage());
            return;
        }

        setBool  (settings, "allowDiagonalAscend",            true);
        setBool  (settings, "allowDiagonalDescend",           true);
        setBool  (settings, "allowOvershootDiagonalDescend",  true);

        setBool  (settings, "allowParkour",                   true);
        setBool  (settings, "allowParkourPlace",              true);
        setBool  (settings, "allowParkourAscend",             true);
        setDouble(settings, "jumpPenalty",                    0.5);

        setBool  (settings, "allowSprint",                    true);
        setBool  (settings, "sprintInWater",                  true);

        setDouble(settings, "walkOnWaterOnePenalty",          0.5);
        setBool  (settings, "allowWaterBucketFall",           true);

        setDouble(settings, "backtrackCostFavoringCoefficient", 1.0);

        setInt   (settings, "maxFallHeightNoWater",           4);

        setBool  (settings, "pathingSmoothLook",              true);
        setBool  (settings, "smoothLook",                     true);
        setInt   (settings, "smoothLookTicks",                4);
        setDouble(settings, "randomLooking",                  0.04);
        setDouble(settings, "randomLooking113",               1.0);

        MindcraftBridgeMod.LOGGER.info("Baritone tuning applied");
    }

    private static void setBool(Object settings, String name, boolean value) {
        setInternal(settings, name, value);
    }

    private static void setInt(Object settings, String name, int value) {
        setInternal(settings, name, value);
    }

    private static void setDouble(Object settings, String name, double value) {
        setInternal(settings, name, value);
    }

    private static void setInternal(Object settings, String name, Object value) {
        try {
            Field settingField = settings.getClass().getField(name);
            Object setting = settingField.get(settings);
            Field valueField = setting.getClass().getField("value");
            valueField.set(setting, value);
        } catch (Throwable t) {
            MindcraftBridgeMod.LOGGER.warn("Baritone setting '{}' not set: {}", name, t.getMessage());
        }
    }
}
