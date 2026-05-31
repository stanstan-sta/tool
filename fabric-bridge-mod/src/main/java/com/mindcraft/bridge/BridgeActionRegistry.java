package com.mindcraft.bridge;

import com.mindcraft.bridge.workers.ActionRegistry;

/**
 * Facade that delegates to {@link ActionRegistry} for all action metadata.
 * Preserves the existing static API surface so callers (BridgeHttpServer,
 * CommandExecutor) do not need import changes.
 */
final class BridgeActionRegistry {

    static final int PROTOCOL_VERSION = 1;
    static final String BRIDGE_VERSION = "1.1.0";
    static final String MINECRAFT_VERSION = "1.21.11";
    static final String DEFAULT_PROVIDER = "baritone_chat";

    static boolean isKnown(String type) {
        return ActionRegistry.get().isExternallyRoutable(type);
    }

    static boolean isSelfExecuting(String type) {
        return ActionRegistry.get().isSelfExecuting(type);
    }

    static String actionTypesJson() {
        return ActionRegistry.get().actionTypesJson();
    }

    static String capabilitiesJson() {
        return ActionRegistry.get().capabilitiesJson();
    }

    private BridgeActionRegistry() {}
}
