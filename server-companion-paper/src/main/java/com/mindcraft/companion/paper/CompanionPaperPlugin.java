package com.mindcraft.companion.paper;

import com.mindcraft.protocol.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CompanionPaperPlugin extends JavaPlugin implements Listener {

    // "mindcraft:companion" — must equal the Fabric client's channel id.
    public static final String CHANNEL = BridgeProtocol.CHANNEL_NAMESPACE + ":" + BridgeProtocol.CHANNEL_PATH;

    private boolean enabled, sendRoster, sendEvents, sendFacts;
    private long broadcastIntervalTicks;
    private final List<String> allowlist = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadCfg();

        // Outgoing (server -> client) channel registration is mandatory before sendPluginMessage.
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);

        if (!enabled) {
            getLogger().info("Disabled in config; listeners not registered.");
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        if (sendRoster) {
            Bukkit.getScheduler().runTaskTimer(this, this::broadcastRoster,
                    broadcastIntervalTicks, broadcastIntervalTicks);
        }
        getLogger().info("Mindcraft Server Companion (Paper) enabled on channel " + CHANNEL);
    }

    private void loadCfg() {
        reloadConfig();
        enabled    = getConfig().getBoolean("enabled", true);
        sendRoster = getConfig().getBoolean("send-roster", true);
        sendEvents = getConfig().getBoolean("send-events", true);
        sendFacts  = getConfig().getBoolean("send-facts", true);
        broadcastIntervalTicks = Math.max(1L, getConfig().getLong("broadcast-interval-ticks", 30L));
        allowlist.clear();
        for (String s : getConfig().getStringList("player-allowlist")) {
            if (s != null && !s.isEmpty()) allowlist.add(s.toLowerCase(Locale.ROOT));
        }
    }

    private boolean isAllowlisted(Player p) {
        if (allowlist.isEmpty()) return false;                       // privacy default: nobody
        return allowlist.contains(p.getName().toLowerCase(Locale.ROOT));
    }

    private List<Player> recipients() {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (isAllowlisted(p)) out.add(p);
        return out;
    }

    // ---------------- listeners ----------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (isAllowlisted(p)) {
            sendTo(p, buildHello());
            if (sendFacts) sendTo(p, buildFacts());
        }
        if (sendEvents) {
            EventMessage ev = newEvent("player_join", p.getName());
            ev.dim = dimOf(p.getWorld());
            broadcastEvent(ev);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (sendEvents) broadcastEvent(newEvent("player_leave", e.getPlayer().getName()));
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (!sendEvents) return;
        EventMessage ev = newEvent("block_break", e.getPlayer().getName());
        ev.dim   = dimOf(e.getPlayer().getWorld());
        ev.block = e.getBlock().getType().getKey().toString();       // "minecraft:stone"
        ev.x = e.getBlock().getX(); ev.y = e.getBlock().getY(); ev.z = e.getBlock().getZ();
        broadcastEvent(ev);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!sendEvents) return;
        Player p = e.getEntity();
        EventMessage ev = newEvent("player_death", p.getName());
        ev.dim = dimOf(p.getWorld());
        if (p.getLastDamageCause() != null) {
            ev.cause = p.getLastDamageCause().getCause().name().toLowerCase(Locale.ROOT);
        }
        Location l = p.getLocation();
        ev.x = l.getBlockX(); ev.y = l.getBlockY(); ev.z = l.getBlockZ();
        broadcastEvent(ev);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        if (!sendEvents) return;
        Player p = e.getPlayer();
        EventMessage ev = newEvent("player_dimension_change", p.getName());
        ev.from = dimOf(e.getFrom());
        ev.to   = dimOf(p.getWorld());
        broadcastEvent(ev);
    }

    // ---------------- builders ----------------

    private HelloMessage buildHello() {
        HelloMessage h = new HelloMessage();
        h.modVersion = "1.0.0";
        h.capabilities = List.of("roster", "event", "facts");
        return h;
    }

    private FactsMessage buildFacts() {
        World w = overworld();
        FactsMessage f = new FactsMessage();
        Location s = w.getSpawnLocation();
        f.spawn = new int[]{ s.getBlockX(), s.getBlockY(), s.getBlockZ() };
        WorldBorder b = w.getWorldBorder();
        f.borderCenterX = b.getCenter().getX();
        f.borderCenterZ = b.getCenter().getZ();
        f.borderSize    = b.getSize();
        return f;
    }

    private void broadcastRoster() {
        List<Player> rec = recipients();
        if (rec.isEmpty()) return;                                   // skip work when nobody listens
        RosterMessage roster = new RosterMessage();
        roster.players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) roster.players.add(playerInfo(p));
        byte[] framed = frame(ProtocolCodec.encode(roster));
        for (Player r : rec) r.sendPluginMessage(this, CHANNEL, framed);
    }

    private PlayerInfo playerInfo(Player p) {
        PlayerInfo i = new PlayerInfo();
        i.name = p.getName();
        i.uuid = p.getUniqueId().toString();
        i.dim  = dimOf(p.getWorld());
        i.gamemode = p.getGameMode().name().toLowerCase(Locale.ROOT);   // "survival"
        ItemStack hand = p.getInventory().getItemInMainHand();
        i.mainhand = (hand == null || hand.getType().isAir())
                ? "minecraft:air" : hand.getType().getKey().toString();
        Location l = p.getLocation();
        i.x = l.getX(); i.y = l.getY(); i.z = l.getZ();
        i.health = p.getHealth();
        i.yaw = l.getYaw(); i.pitch = l.getPitch();
        i.sneaking  = p.isSneaking();
        i.inVehicle = p.isInsideVehicle();
        return i;
    }

    private EventMessage newEvent(String kind, String player) {
        EventMessage ev = new EventMessage();
        ev.kind = kind; ev.player = player; ev.ts = System.currentTimeMillis();
        return ev;
    }

    // ---------------- send + framing ----------------

    private void broadcastEvent(EventMessage ev) {
        byte[] framed = frame(ProtocolCodec.encode(ev));
        for (Player r : recipients()) r.sendPluginMessage(this, CHANNEL, framed);
    }

    private void sendTo(Player p, Object message) {
        p.sendPluginMessage(this, CHANNEL, frame(ProtocolCodec.encode(message)));
    }

    /**
     * Frame a JSON string EXACTLY as Minecraft's PacketByteBuf string codec expects:
     * VarInt(utf8ByteLength) followed by the UTF-8 bytes. The Fabric client decodes this with
     * PacketCodecs.string(262144). DO NOT use DataOutputStream.writeUTF (that is a 2-byte short).
     */
    static byte[] frame(String json) {
        byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(utf8.length + 5);
        writeVarInt(out, utf8.length);
        out.write(utf8, 0, utf8.length);
        return out.toByteArray();
    }

    /** Standard Minecraft VarInt: 7 data bits per byte, high bit (0x80) = "more bytes follow". */
    static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    // ---------------- helpers ----------------

    private World overworld() {
        for (World w : Bukkit.getWorlds()) if (w.getEnvironment() == World.Environment.NORMAL) return w;
        return Bukkit.getWorlds().get(0);
    }

    private static String dimOf(World w) {
        switch (w.getEnvironment()) {
            case NORMAL:  return "minecraft:overworld";
            case NETHER:  return "minecraft:the_nether";
            case THE_END: return "minecraft:the_end";
            default:      return w.getKey().toString();
        }
    }
}
