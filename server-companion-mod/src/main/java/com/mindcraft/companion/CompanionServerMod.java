package com.mindcraft.companion;

import com.mindcraft.protocol.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class CompanionServerMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("mindcraft-server-companion");

    private CompanionConfig config;
    private MinecraftServer server;
    private long lastRosterSend = 0;

    @Override
    public void onInitialize() {
        LOGGER.info("Initialising Mindcraft Server Companion mod...");
        this.config = CompanionConfig.get();

        PayloadTypeRegistry.playS2C().register(CompanionPayload.ID, CompanionPayload.CODEC);
        LOGGER.info("Registered companion payload channel");

        ServerLifecycleEvents.SERVER_STARTED.register(s -> this.server = s);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            if (!config.enabled) return;
            ServerPlayerEntity player = handler.getPlayer();
            if (!isAllowlisted(player)) return;
            sendHello(player);
            if (config.sendFacts) {
                sendFacts(player);
            }
            if (config.sendEvents) {
                broadcastEvent(buildJoinEvent(player), s);
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            if (!config.enabled || !config.sendEvents) return;
            ServerPlayerEntity player = handler.getPlayer();
            broadcastEvent(buildLeaveEvent(player), s);
        });

        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (!config.enabled || !config.sendRoster) return;
            long now = System.currentTimeMillis();
            if (now - lastRosterSend < config.broadcastIntervalMs) return;
            lastRosterSend = now;
            sendRoster(s);
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, entity) -> {
            if (!config.enabled || !config.sendEvents) return;
            if (!(player instanceof ServerPlayerEntity)) return;
            broadcastEvent(buildBlockBreakEvent((ServerPlayerEntity) player, pos, state), server);
        });

        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            if (!config.enabled || !config.sendEvents) return;
            broadcastEvent(buildDimensionChangeEvent(player, origin, destination), server);
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!config.enabled || !config.sendEvents) return;
            if (!(entity instanceof ServerPlayerEntity)) return;
            broadcastEvent(buildDeathEvent((ServerPlayerEntity) entity, source), server);
        });

        LOGGER.info("Mindcraft Server Companion mod initialised");
    }

    // --- Pure builders (testable without Minecraft, plain inputs only) ---

    public static PlayerInfo buildPlayerInfo(String name, String uuid, String dim, String gamemode,
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

    public static EventMessage buildEvent(String kind, String player, String dim, String block,
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

    public static FactsMessage buildFacts(int spawnX, int spawnY, int spawnZ,
                                          double borderCenterX, double borderCenterZ, double borderSize) {
        FactsMessage facts = new FactsMessage();
        facts.spawn = new int[] { spawnX, spawnY, spawnZ };
        facts.borderCenterX = borderCenterX;
        facts.borderCenterZ = borderCenterZ;
        facts.borderSize = borderSize;
        return facts;
    }

    // --- Minecraft-object → DTO wrappers (thin, not unit-testable without server) ---

    public static EventMessage buildBlockBreakEvent(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        return buildEvent("block_break", player.getName().getString(),
            player.getEntityWorld().getRegistryKey().getValue().toString(),
            Registries.BLOCK.getId(state.getBlock()).toString(),
            null, null, null,
            pos.getX(), pos.getY(), pos.getZ(), System.currentTimeMillis());
    }

    public static EventMessage buildJoinEvent(ServerPlayerEntity player) {
        return buildEvent("player_join", player.getName().getString(),
            player.getEntityWorld().getRegistryKey().getValue().toString(),
            null, null, null, null,
            null, null, null, System.currentTimeMillis());
    }

    public static EventMessage buildLeaveEvent(ServerPlayerEntity player) {
        return buildEvent("player_leave", player.getName().getString(),
            null, null, null, null, null,
            null, null, null, System.currentTimeMillis());
    }

    public static EventMessage buildDeathEvent(ServerPlayerEntity player, DamageSource source) {
        return buildEvent("player_death", player.getName().getString(),
            player.getEntityWorld().getRegistryKey().getValue().toString(),
            null, source.getName(), null, null,
            (int) player.getX(), (int) player.getY(), (int) player.getZ(), System.currentTimeMillis());
    }

    public static EventMessage buildDimensionChangeEvent(ServerPlayerEntity player,
                                                          ServerWorld origin, ServerWorld destination) {
        return buildEvent("player_dimension_change", player.getName().getString(),
            null, null, null,
            origin.getRegistryKey().getValue().toString(),
            destination.getRegistryKey().getValue().toString(),
            null, null, null, System.currentTimeMillis());
    }

    // --- Minecraft-object-to-DTO mapping (thin) ---

    private PlayerInfo fromPlayer(ServerPlayerEntity player) {
        ItemStack mainHand = player.getMainHandStack();
        String mainhandId = mainHand.isEmpty() ? "minecraft:air" : Registries.ITEM.getId(mainHand.getItem()).toString();
        return buildPlayerInfo(
            player.getName().getString(),
            player.getUuidAsString(),
            player.getEntityWorld().getRegistryKey().getValue().toString(),
            player.interactionManager.getGameMode().asString(),
            mainhandId,
            player.getX(), player.getY(), player.getZ(),
            player.getHealth(),
            player.getYaw(), player.getPitch(),
            player.isSneaking(),
            player.getVehicle() != null
        );
    }

    // --- Send helpers ---

    private boolean isAllowlisted(ServerPlayerEntity player) {
        if (config.playerAllowlist == null || config.playerAllowlist.isEmpty()) {
            return false;
        }
        String name = player.getName().getString();
        for (String entry : config.playerAllowlist) {
            if (entry.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private List<ServerPlayerEntity> allowlistedRecipients() {
        List<ServerPlayerEntity> recipients = new ArrayList<>();
        if (server == null) return recipients;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (isAllowlisted(player)) recipients.add(player);
        }
        return recipients;
    }

    private void sendHello(ServerPlayerEntity recipient) {
        HelloMessage hello = new HelloMessage();
        hello.modVersion = "1.0.0";
        hello.capabilities = List.of("roster", "event", "facts");
        sendPayload(recipient, hello);
    }

    private void sendFacts(ServerPlayerEntity recipient) {
        MinecraftServer srv = recipient.getEntityWorld().getServer();
        if (srv == null) {
            return;
        }
        ServerWorld overworld = srv.getOverworld();
        if (overworld == null) {
            return;
        }
        BlockPos spawn = overworld.getSpawnPoint().getPos();
        WorldBorder border = overworld.getWorldBorder();
        FactsMessage facts = buildFacts(
            spawn.getX(), spawn.getY(), spawn.getZ(),
            border.getCenterX(), border.getCenterZ(), border.getSize());
        sendPayload(recipient, facts);
    }

    private void sendRoster(MinecraftServer server) {
        List<ServerPlayerEntity> recipients = allowlistedRecipients();
        if (recipients.isEmpty()) return;

        RosterMessage roster = new RosterMessage();
        roster.players = new ArrayList<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            roster.players.add(fromPlayer(p));
        }

        String json = ProtocolCodec.encode(roster);
        CompanionPayload payload = new CompanionPayload(json);
        for (ServerPlayerEntity r : recipients) {
            try {
                ServerPlayNetworking.send(r, payload);
            } catch (Exception e) {
                LOGGER.error("Failed to send roster to {}", r.getName().getString(), e);
            }
        }
        LOGGER.debug("Sent roster to {} recipient(s)", recipients.size());
    }

    private void broadcastEvent(EventMessage event, MinecraftServer server) {
        if (server == null) return;
        String json = ProtocolCodec.encode(event);
        CompanionPayload payload = new CompanionPayload(json);
        for (ServerPlayerEntity r : allowlistedRecipients()) {
            try {
                ServerPlayNetworking.send(r, payload);
            } catch (Exception e) {
                LOGGER.error("Failed to send event to {}", r.getName().getString(), e);
            }
        }
        LOGGER.debug("Broadcast event: {}", event.kind);
    }

    private void sendPayload(ServerPlayerEntity recipient, Object message) {
        try {
            String json = ProtocolCodec.encode(message);
            ServerPlayNetworking.send(recipient, new CompanionPayload(json));
        } catch (Exception e) {
            LOGGER.error("Failed to send payload to {}", recipient.getName().getString(), e);
        }
    }
}
