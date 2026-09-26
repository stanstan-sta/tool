package com.mindcraft.companion;

import java.util.ArrayList;
import java.util.List;

import com.mindcraft.protocol.BridgeProtocol;
import com.mindcraft.protocol.EventMessage;
import com.mindcraft.protocol.FactsMessage;
import com.mindcraft.protocol.HelloMessage;
import com.mindcraft.protocol.PlayerInfo;
import com.mindcraft.protocol.ProtocolCodec;
import com.mindcraft.protocol.RosterMessage;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompanionServerMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-companion");
    private static final String MOD_VERSION = "1.0.0";
    private static final List<String> CAPABILITIES = List.of("roster", "event", "facts");

    private CompanionConfig config;
    private long lastRosterSend = 0;

    @Override
    public void onInitialize() {
        LOGGER.info("Mindcraft Companion Mod initialising...");

        PayloadTypeRegistry.playS2C().register(CompanionPayload.ID, CompanionPayload.CODEC);

        config = CompanionConfig.load(
            FabricLoader.getInstance().getConfigDir()
        );

        if (!config.enabled) {
            LOGGER.info("Mindcraft Companion Mod is disabled via config");
            return;
        }

        registerJoinListener();
        registerLeaveListener();
        registerRosterTimer();
        registerBlockBreakListener();
        registerDimensionChangeListener();
        registerDeathListener();

        LOGGER.info("Mindcraft Companion Mod initialised. Allowlisted players: {}",
            config.playerAllowlist.isEmpty() ? "none (no data sent)" : config.playerAllowlist);
    }

    private void registerJoinListener() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            if (!isAllowlisted(player)) return;

            sendHello(player);
            sendFacts(player, server);
        });
    }

    private void registerLeaveListener() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            if (!isAllowlisted(player)) return;

            EventMessage event = buildEvent(
                "player_leave", player.getName().getString(),
                null, null, null, null, null,
                null, null, null, System.currentTimeMillis()
            );

            broadcastToAllowlisted(server, event);
        });
    }

    private void registerRosterTimer() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!config.sendRoster) return;
            long now = System.currentTimeMillis();
            if (now - lastRosterSend < config.broadcastIntervalMs) return;
            lastRosterSend = now;

            RosterMessage roster = buildRoster(server);
            String json = ProtocolCodec.encode(roster);
            CompanionPayload payload = new CompanionPayload(json);

            for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
                if (isAllowlisted(recipient)) {
                    try {
                        ServerPlayNetworking.send(recipient, payload);
                    } catch (Exception e) {
                        LOGGER.error("Failed to send roster to {}", recipient.getName().getString(), e);
                    }
                }
            }
        });
    }

    private void registerBlockBreakListener() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!config.sendEvents) return;
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return;
            if (!isAllowlisted(serverPlayer)) return;

            EventMessage event = buildEvent(
                "block_break", serverPlayer.getName().getString(),
                world.getRegistryKey().getValue().toString(),
                state.getBlock().getTranslationKey(),
                null, null, null,
                pos.getX(), pos.getY(), pos.getZ(),
                System.currentTimeMillis()
            );

            broadcastToAllowlisted(world.getServer(), event);
        });
    }

    private void registerDimensionChangeListener() {
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            if (!config.sendEvents) return;
            if (!isAllowlisted(player)) return;

            EventMessage event = buildEvent(
                "player_dimension_change", player.getName().getString(),
                null, null, null,
                origin.getRegistryKey().getValue().toString(),
                destination.getRegistryKey().getValue().toString(),
                null, null, null,
                System.currentTimeMillis()
            );

            broadcastToAllowlisted(((ServerWorld)player.getEntityWorld()).getServer(), event);
        });
    }

    private void registerDeathListener() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!config.sendEvents) return;
            if (!(entity instanceof ServerPlayerEntity player)) return;
            if (!isAllowlisted(player)) return;

            EventMessage event = buildEvent(
                "player_death", player.getName().getString(),
                null, null, damageSource.getName(),
                null, null,
                null, null, null,
                System.currentTimeMillis()
            );

            broadcastToAllowlisted(((ServerWorld)player.getEntityWorld()).getServer(), event);
        });
    }

    // --- Pure DTO builders (testable without Minecraft objects) ---

    public static PlayerInfo buildPlayerInfo(
            String name, String uuid, String dim, String gamemode,
            String mainhand, double x, double y, double z,
            double health, float yaw, float pitch,
            boolean sneaking, boolean inVehicle) {
        PlayerInfo info = new PlayerInfo();
        info.name = name;
        info.uuid = uuid;
        info.dim = dim;
        info.gamemode = gamemode;
        info.mainhand = mainhand;
        info.x = x;
        info.y = y;
        info.z = z;
        info.health = health;
        info.yaw = yaw;
        info.pitch = pitch;
        info.sneaking = sneaking;
        info.inVehicle = inVehicle;
        return info;
    }

    public static EventMessage buildEvent(
            String kind, String player, String dim, String block,
            String cause, String from, String to,
            Integer x, Integer y, Integer z, Long ts) {
        EventMessage event = new EventMessage();
        event.kind = kind;
        event.player = player;
        event.dim = dim;
        event.block = block;
        event.cause = cause;
        event.from = from;
        event.to = to;
        event.x = x;
        event.y = y;
        event.z = z;
        event.ts = ts;
        return event;
    }

    public static FactsMessage buildFacts(int[] spawn, Double borderCenterX, Double borderCenterZ, Double borderSize) {
        FactsMessage facts = new FactsMessage();
        facts.spawn = spawn;
        facts.borderCenterX = borderCenterX;
        facts.borderCenterZ = borderCenterZ;
        facts.borderSize = borderSize;
        return facts;
    }

    // --- Minecraft-object -> DTO mapping (thin, untested) ---

    private PlayerInfo fromServerPlayer(ServerPlayerEntity player) {
        String mainhandId = player.getMainHandStack().isEmpty()
            ? "minecraft:air"
            : player.getMainHandStack().getItem().toString();
        return buildPlayerInfo(
            player.getName().getString(),
            player.getUuid().toString(),
            ((ServerWorld)player.getEntityWorld()).getRegistryKey().getValue().toString(),
            player.interactionManager.getGameMode().name().toLowerCase(),
            mainhandId,
            player.getX(), player.getY(), player.getZ(),
            player.getHealth(),
            player.getYaw(), player.getPitch(),
            player.isSneaking(),
            player.hasVehicle()
        );
    }

    // --- Internal helpers ---

    private RosterMessage buildRoster(MinecraftServer server) {
        List<PlayerInfo> players = new ArrayList<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            players.add(fromServerPlayer(p));
        }
        RosterMessage roster = new RosterMessage();
        roster.players = players;
        return roster;
    }

    private void sendHello(ServerPlayerEntity player) {
        HelloMessage hello = new HelloMessage();
        hello.modVersion = MOD_VERSION;
        hello.capabilities = CAPABILITIES;
        send(player, hello);
    }

    private void sendFacts(ServerPlayerEntity player, MinecraftServer server) {
        FactsMessage facts = new FactsMessage();
        ServerWorld world = server.getWorld(World.OVERWORLD);
        if (world != null) {
            BlockPos spawn = world.getSpawnPoint().getPos();
            facts.spawn = new int[]{spawn.getX(), spawn.getY(), spawn.getZ()};
            facts.borderCenterX = world.getWorldBorder().getCenterX();
            facts.borderCenterZ = world.getWorldBorder().getCenterZ();
            facts.borderSize = world.getWorldBorder().getSize();
        }
        send(player, facts);
    }

    private void send(ServerPlayerEntity player, Object message) {
        String json = ProtocolCodec.encode(message);
        try {
            ServerPlayNetworking.send(player, new CompanionPayload(json));
        } catch (Exception e) {
            LOGGER.error("Failed to send {} to {}",
                ProtocolCodec.peekType(json), player.getName().getString(), e);
        }
    }

    private void broadcastToAllowlisted(MinecraftServer server, Object message) {
        if (server == null) return;
        String json = ProtocolCodec.encode(message);
        CompanionPayload payload = new CompanionPayload(json);

        for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
            if (isAllowlisted(recipient)) {
                try {
                    ServerPlayNetworking.send(recipient, payload);
                } catch (Exception e) {
                    LOGGER.error("Failed to broadcast event to {}",
                        recipient.getName().getString(), e);
                }
            }
        }
    }

    private boolean isAllowlisted(ServerPlayerEntity player) {
        return config.playerAllowlist.contains(player.getGameProfile().name());
    }
}