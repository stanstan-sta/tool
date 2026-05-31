package com.mindcraft.bridge;

import java.util.Locale;

public final class RawCommandPolicy {
    private RawCommandPolicy() {}

    /**
     * Check whether a raw command is allowed by prefix matching against the
     * configured allowlist. The command must start with '#' and match one of
     * the allowlist prefixes (e.g. "#sleep 5" matches "#sleep").
     */
    public static boolean isAllowed(String command) {
        if (command == null) return false;

        String normalized = command
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);

        if (normalized.isBlank()) return false;
        if (!normalized.startsWith("#")) return false;

        BridgeConfig config = BridgeConfig.get();
        if (!config.enableRawCommand) return false;
        if (config.rawCommandAllowlist == null) return false;

        for (String rawPrefix : config.rawCommandAllowlist) {
            if (rawPrefix == null) continue;
            String prefix = rawPrefix
                    .trim()
                    .replaceAll("\\s+", " ")
                    .toLowerCase(Locale.ROOT);
            if (prefix.isBlank()) continue;
            if (normalized.equals(prefix) || normalized.startsWith(prefix + " ")) {
                return true;
            }
        }

        return false;
    }
}
