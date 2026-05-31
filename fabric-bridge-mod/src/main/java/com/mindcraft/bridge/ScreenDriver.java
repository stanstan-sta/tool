package com.mindcraft.bridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

public final class ScreenDriver {
    private ScreenDriver() {}

    public static boolean waitForHandler(Class<?> handlerClass, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Boolean open = ClientThread.call(() -> {
                ClientPlayerEntity p = MinecraftClient.getInstance().player;
                return p != null && handlerClass.isInstance(p.currentScreenHandler);
            });
            if (Boolean.TRUE.equals(open)) return true;
            sleep(50);
        }
        return false;
    }

    public static boolean click(int slot, int button, SlotActionType actionType) {
        return click(slot, button, actionType, null);
    }

    public static boolean click(int slot, int button, SlotActionType actionType, Class<? extends ScreenHandler> expectedHandlerType) {
        boolean ok = Boolean.TRUE.equals(ClientThread.call(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity p = client.player;
            ClientPlayerInteractionManager im = client.interactionManager;
            if (p == null || im == null) return false;
            ScreenHandler handler = p.currentScreenHandler;
            if (!isUsableHandler(handler, slot, expectedHandlerType)) return false;
            im.clickSlot(handler.syncId, slot, button, actionType, p);
            return true;
        }));
        sleep(50L);
        return ok;
    }

    public static boolean quickMove(int slot) {
        return quickMove(slot, null);
    }

    public static boolean quickMove(int slot, Class<? extends ScreenHandler> expectedHandlerType) {
        return click(slot, 0, SlotActionType.QUICK_MOVE, expectedHandlerType);
    }

    public static boolean pickup(int slot) {
        return pickup(slot, null);
    }

    public static boolean pickup(int slot, Class<? extends ScreenHandler> expectedHandlerType) {
        return click(slot, 0, SlotActionType.PICKUP, expectedHandlerType);
    }

    static boolean swap(int sourceSlot, int targetSlot) {
        return swap(sourceSlot, targetSlot, null);
    }

    static boolean swap(int sourceSlot, int targetSlot, Class<? extends ScreenHandler> expectedHandlerType) {
        if (!pickup(sourceSlot, expectedHandlerType)) return false;
        if (!pickup(targetSlot, expectedHandlerType)) return false;
        return pickup(sourceSlot, expectedHandlerType);
    }

    public static int findSlot(ScreenHandler screen, String itemId, int startSlot) {
        for (int i = startSlot; i < screen.slots.size(); i++) {
            ItemStack stack = screen.getSlot(i).getStack();
            if (!stack.isEmpty() && ItemIds.fromStack(stack).equals(itemId)) {
                return i;
            }
        }
        return -1;
    }

    public static void closeScreen() {
        ClientThread.run(() -> {
            ClientPlayerEntity p = MinecraftClient.getInstance().player;
            if (p != null) p.closeHandledScreen();
        });
        sleep(100);
    }

    static String describeOpenHandler() {
        return ClientThread.call(() -> {
            ClientPlayerEntity p = MinecraftClient.getInstance().player;
            if (p == null || p.currentScreenHandler == null) return "none";
            return p.currentScreenHandler.getClass().getSimpleName();
        });
    }

    /**
     * Click a slot and verify the result matches expectations.
     * Handles inventory desync by waiting for server acknowledgment.
     *
     * @param slot The slot index to click
     * @param button The mouse button (0=left, 1=right)
     * @param actionType The action type (PICKUP, QUICK_MOVE, etc.)
     * @param expectedItemId Expected item in slot after click (null = empty)
     * @param expectedCount Expected minimum count (0 = any)
     * @return true if click succeeded and verification passed
     */
    static boolean clickAndVerify(int slot, int button, SlotActionType actionType,
                                    String expectedItemId, int expectedCount) {
        return clickAndVerify(slot, button, actionType, expectedItemId, expectedCount, null);
    }

    static boolean clickAndVerify(int slot, int button, SlotActionType actionType,
                                    String expectedItemId, int expectedCount,
                                    Class<? extends ScreenHandler> expectedHandlerType) {
        boolean clicked = click(slot, button, actionType, expectedHandlerType);
        if (!clicked) return false;
        sleep(100);
        return Boolean.TRUE.equals(ClientThread.call(() -> {
            ClientPlayerEntity p = MinecraftClient.getInstance().player;
            if (p == null) return false;
            ScreenHandler handler = p.currentScreenHandler;
            if (!isUsableHandler(handler, slot, expectedHandlerType)) return false;
            ItemStack stack = handler.getSlot(slot).getStack();
            if (expectedItemId == null) return stack.isEmpty();
            String actualId = ItemIds.fromStack(stack);
            return actualId.equals(expectedItemId) && stack.getCount() >= expectedCount;
        }));
    }

    /**
     * Verify a slot contains the expected item without clicking.
     */
    static boolean verifySlot(int slot, String expectedItemId, int expectedCount) {
        return verifySlot(slot, expectedItemId, expectedCount, null);
    }

    static boolean verifySlot(int slot, String expectedItemId, int expectedCount,
                               Class<? extends ScreenHandler> expectedHandlerType) {
        return Boolean.TRUE.equals(ClientThread.call(() -> {
            ClientPlayerEntity p = MinecraftClient.getInstance().player;
            if (p == null) return false;
            ScreenHandler handler = p.currentScreenHandler;
            if (!isUsableHandler(handler, slot, expectedHandlerType)) return false;
            ItemStack stack = handler.getSlot(slot).getStack();
            if (expectedItemId == null) return stack.isEmpty();
            String actualId = ItemIds.fromStack(stack);
            return actualId.equals(expectedItemId) && stack.getCount() >= expectedCount;
        }));
    }

    /**
     * Click with desync recovery — if verification fails, close/reopen screen and retry.
     */
    static boolean clickWithRecovery(int slot, int button, SlotActionType actionType,
                                       String expectedItemId, int expectedCount,
                                       BlockPos workstation) {
        return clickWithRecovery(slot, button, actionType, expectedItemId, expectedCount, workstation, null);
    }

    static boolean clickWithRecovery(int slot, int button, SlotActionType actionType,
                                      String expectedItemId, int expectedCount,
                                      BlockPos workstation, Class<? extends ScreenHandler> expectedHandlerType) {
        if (clickAndVerify(slot, button, actionType, expectedItemId, expectedCount, expectedHandlerType)) {
            return true;
        }
        closeScreen();
        sleep(200);
        if (workstation != null) {
            WorldInteractor.openBlock(workstation);
            waitForHandler(expectedHandlerType, 3000);
        }
        return clickAndVerify(slot, button, actionType, expectedItemId, expectedCount, expectedHandlerType);
    }

    /**
     * Pickup with verify: pick up from source, place in target, verify target has item.
     */
    static boolean pickupAndVerify(int sourceSlot, int targetSlot,
                                      String expectedItemId, int expectedCount) {
        return pickupAndVerify(sourceSlot, targetSlot, expectedItemId, expectedCount, null);
    }

    static boolean pickupAndVerify(int sourceSlot, int targetSlot,
                                      String expectedItemId, int expectedCount,
                                      Class<? extends ScreenHandler> expectedHandlerType) {
        if (!click(sourceSlot, 0, SlotActionType.PICKUP, expectedHandlerType)) return false;
        if (!click(targetSlot, 0, SlotActionType.PICKUP, expectedHandlerType)) {
            click(sourceSlot, 0, SlotActionType.PICKUP, expectedHandlerType);
            return false;
        }
        click(sourceSlot, 0, SlotActionType.PICKUP, expectedHandlerType);
        sleep(100);
        return verifySlot(targetSlot, expectedItemId, expectedCount, expectedHandlerType);
    }

    private static boolean isUsableHandler(ScreenHandler handler, int slot, Class<? extends ScreenHandler> expectedType) {
        if (handler == null) return false;
        if (handler.syncId == 0) return false;
        if (expectedType != null && !expectedType.isInstance(handler)) return false;
        return slot >= 0 && slot < handler.slots.size();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
