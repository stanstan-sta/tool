package com.mindcraft.bridge;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    record StateDescriptor(String blockId, Map<String, String> properties) {}

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
            paletteStates[i] = resolveState(paletteIds.get(i));
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

    static BlockState resolveState(String descriptor) {
        BlockState parsed = parseState(descriptor);
        return parsed != null ? parsed : Blocks.AIR.getDefaultState();
    }

    static BlockState parseState(String descriptor) {
        StateDescriptor parsedDescriptor = parseDescriptor(descriptor);
        if (parsedDescriptor == null) return null;
        try {
            Identifier ident = Identifier.tryParse(parsedDescriptor.blockId());
            if (ident == null) return null;
            Block block = Registries.BLOCK.get(ident);
            if (block == null) return null;
            BlockState state = block.getDefaultState();
            for (Map.Entry<String, String> assignment : parsedDescriptor.properties().entrySet()) {
                Property<?> property = block.getStateManager().getProperty(assignment.getKey());
                if (property == null) return null;
                state = withParsedProperty(state, property, assignment.getValue());
                if (state == null) return null;
            }
            return state;
        } catch (Throwable t) {
            return null;
        }
    }

    static StateDescriptor parseDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) return null;
        String value = descriptor.trim();
        int bracket = value.indexOf('[');
        String id = bracket >= 0 ? value.substring(0, bracket) : value;
        String normalized = id.contains(":") ? id : ("minecraft:" + id);
        if (Identifier.tryParse(normalized) == null) return null;
        Map<String, String> properties = new LinkedHashMap<>();
        if (bracket < 0) return new StateDescriptor(normalized, properties);
        if (!value.endsWith("]") || bracket == value.length() - 1) return null;
        String assignments = value.substring(bracket + 1, value.length() - 1);
        if (assignments.isBlank()) return new StateDescriptor(normalized, properties);
        for (String assignment : assignments.split(",")) {
            String[] parts = assignment.split("=", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) return null;
            if (properties.put(parts[0].trim(), parts[1].trim()) != null) return null;
        }
        return new StateDescriptor(normalized, properties);
    }

    private static <T extends Comparable<T>> BlockState withParsedProperty(
            BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.parse(value);
        return parsed.map(entry -> state.with(property, entry)).orElse(null);
    }

    static String serializeState(BlockState state) {
        if (state == null) return "minecraft:air";
        String id = Registries.BLOCK.getId(state.getBlock()).toString();
        List<Map.Entry<Property<?>, Comparable<?>>> entries = new ArrayList<>(state.getEntries().entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey().getName()));
        if (entries.isEmpty()) return id;
        StringBuilder out = new StringBuilder(id).append('[');
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) out.append(',');
            Map.Entry<Property<?>, Comparable<?>> entry = entries.get(i);
            out.append(entry.getKey().getName()).append('=').append(propertyValue(entry));
        }
        return out.append(']').toString();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValue(Map.Entry<Property<?>, Comparable<?>> entry) {
        return ((Property) entry.getKey()).name(entry.getValue());
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
