package com.mindcraft.bridge;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * In-memory schematic that implements Baritone's
 * {@code baritone.api.schematic.ISchematic} via a dynamic proxy.
 *
 * <p>We don't have Baritone on the compile classpath (see README / SETUP),
 * so the interface is loaded reflectively at call time. This intentionally
 * mirrors what Baritone's {@code SpongeSchematic} / {@code StaticSchematic}
 * produce in memory, but skips the NBT disk round-trip.
 *
 * <p>Block storage is indexed as {@code blocks[(y * length + z) * width + x]},
 * matching the Sponge schematic packing order so the same math works if we
 * ever serialize later.
 */
public final class BridgeSchematic {

    private BridgeSchematic() {}

    /**
     * Build a proxy of {@code baritone.api.schematic.ISchematic} from the given
     * palette + block index array. Returns {@code null} on any reflection or
     * block-resolution failure.
     *
     * @param paletteIds block identifiers (e.g. {@code "minecraft:oak_planks"})
     *                   indexed 0..N. Unknown identifiers fall back to air.
     * @param blocks     index-into-palette for each cell, packed XZY
     * @param width      X size (must match blocks layout)
     * @param height     Y size
     * @param length     Z size
     */
    public static Object createProxy(List<String> paletteIds,
                                     short[] blocks,
                                     int width,
                                     int height,
                                     int length) {
        if (paletteIds == null || paletteIds.isEmpty()) return null;
        if (blocks == null) return null;
        if (width <= 0 || height <= 0 || length <= 0) return null;
        if (blocks.length != width * height * length) return null;

        // Resolve palette once, in advance. Missing identifiers fall back to air.
        BlockState[] paletteStates = new BlockState[paletteIds.size()];
        for (int i = 0; i < paletteIds.size(); i++) {
            paletteStates[i] = resolveBlock(paletteIds.get(i));
        }

        Class<?> iface;
        try {
            iface = Class.forName("baritone.api.schematic.ISchematic");
        } catch (Throwable t) {
            return null;
        }

        final int w = width;
        final int h = height;
        final int l = length;
        InvocationHandler handler = new SchematicHandler(paletteStates, blocks, w, h, l);
        try {
            return Proxy.newProxyInstance(
                    iface.getClassLoader(),
                    new Class<?>[] { iface },
                    handler
            );
        } catch (Throwable t) {
            return null;
        }
    }

    private static BlockState resolveBlock(String id) {
        if (id == null || id.isBlank()) return Blocks.AIR.getDefaultState();
        String normalized = id.contains(":") ? id : ("minecraft:" + id);
        try {
            Identifier ident = Identifier.tryParse(normalized);
            if (ident == null) return Blocks.AIR.getDefaultState();
            Block block = Registries.BLOCK.get(ident);
            if (block == null || block == Blocks.AIR) return Blocks.AIR.getDefaultState();
            return block.getDefaultState();
        } catch (Throwable t) {
            return Blocks.AIR.getDefaultState();
        }
    }

    /**
     * Invocation handler that answers Baritone's ISchematic method calls
     * with the block data we hold. All methods with non-primitive returns
     * that we don't need (like {@code reset()}) fall through to void / null.
     */
    private static final class SchematicHandler implements InvocationHandler {
        private final BlockState[] palette;
        private final short[] blocks;
        private final int width;
        private final int height;
        private final int length;
        private final BlockState air;

        SchematicHandler(BlockState[] palette, short[] blocks, int width, int height, int length) {
            this.palette = palette;
            this.blocks = blocks;
            this.width = width;
            this.height = height;
            this.length = length;
            this.air = Blocks.AIR.getDefaultState();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            switch (name) {
                case "widthX":  return width;
                case "heightY": return height;
                case "lengthZ": return length;
                case "inSchematic": {
                    if (args != null && args.length >= 3) {
                        int x = (Integer) args[0];
                        int y = (Integer) args[1];
                        int z = (Integer) args[2];
                        return x >= 0 && x < width && y >= 0 && y < height && z >= 0 && z < length;
                    }
                    return Boolean.FALSE;
                }
                case "desiredState": {
                    int x = (Integer) args[0];
                    int y = (Integer) args[1];
                    int z = (Integer) args[2];
                    return stateAt(x, y, z);
                }
                case "reset": return null;
                case "size": {
                    // Direction.Axis { X, Y, Z }
                    if (args != null && args.length == 1 && args[0] != null) {
                        String axis = args[0].toString();
                        if ("X".equalsIgnoreCase(axis)) return width;
                        if ("Y".equalsIgnoreCase(axis)) return height;
                        if ("Z".equalsIgnoreCase(axis)) return length;
                    }
                    return width;
                }
                case "toString": return "BridgeSchematic[" + width + "x" + height + "x" + length + "]";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals":   return args != null && args.length == 1 && args[0] == proxy;
                default:
                    // Default interface methods we don't implement are fine
                    // because Baritone's ISchematic has defaults for them.
                    // Anything unexpected returns null.
                    return null;
            }
        }

        private BlockState stateAt(int x, int y, int z) {
            if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= length) return air;
            int idx = (y * length + z) * width + x;
            short paletteIdx = blocks[idx];
            if (paletteIdx < 0 || paletteIdx >= palette.length) return air;
            BlockState st = palette[paletteIdx];
            return st != null ? st : air;
        }
    }

}
