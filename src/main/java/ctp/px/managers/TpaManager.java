package ctp.px.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class TpaManager {
    private static final int REQUEST_DURATION_TICKS = 600;
    private static final int WARMUP_DURATION_SECONDS = 5;
    private static final int COOLDOWN_DURATION_SECONDS = 25;
    private static final double MOVEMENT_THRESHOLD_SQUARED = 0.01;

    private final JavaPlugin plugin;
    private final Map<UUID, Request> requestsByRecipient = new HashMap<>();
    private final Map<UUID, Request> requestsBySender = new HashMap<>();
    private final Map<UUID, Warmup> warmups = new HashMap<>();
    private final Map<UUID, Cooldown> cooldowns = new HashMap<>();

    public TpaManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void request(Player sender, String targetName, RequestType type) {
        String permission = type == RequestType.TPA ? "tpa.use" : "tpa.tpahere";
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(message("&cYou don't have permission to use this command."));
            return;
        }

        if (hasCooldown(sender.getUniqueId())) {
            sender.sendMessage(message("&cYou must wait before sending another teleport request."));
            return;
        }

        Player recipient = Bukkit.getPlayerExact(targetName);
        if (recipient == null) {
            sender.sendMessage(message("&cThat player is not online."));
            return;
        }

        if (recipient.getUniqueId().equals(sender.getUniqueId())) {
            sender.sendMessage(message("&cYou cannot send a teleport request to yourself."));
            return;
        }

        if (requestsByRecipient.containsKey(recipient.getUniqueId())) {
            sender.sendMessage(message("&cThat player already has a pending request."));
            return;
        }

        if (requestsBySender.containsKey(sender.getUniqueId())) {
            sender.sendMessage(message("&cYou already have a pending request."));
            return;
        }

        Request request = new Request(sender.getUniqueId(), recipient.getUniqueId(), type);
        requestsByRecipient.put(recipient.getUniqueId(), request);
        requestsBySender.put(sender.getUniqueId(), request);
        request.expirationTask = Bukkit.getScheduler().runTaskLater(plugin, () -> expireRequest(request), REQUEST_DURATION_TICKS);

        if (type == RequestType.TPA) {
            recipient.sendMessage(message("&e" + sender.getName() + " wants to teleport to you."));
        } else {
            recipient.sendMessage(message("&e" + sender.getName() + " wants you to teleport to them."));
        }
        recipient.sendMessage(message("&eUse /" + type.command + " accept or /" + type.command + " deny."));
        sender.sendMessage(message("&aTeleport request sent to " + recipient.getName() + "."));
    }

    public void accept(Player recipient, RequestType commandType) {
        Request request = requestsByRecipient.get(recipient.getUniqueId());
        if (request == null) {
            recipient.sendMessage(message("&cYou don't have a pending teleport request."));
            return;
        }
        if (request.type != commandType) {
            recipient.sendMessage(message("&cThis is a " + request.type.displayName + ". Use /" + request.type.command + " accept."));
            return;
        }
        if (!recipient.hasPermission(permissionFor(commandType))) {
            recipient.sendMessage(message("&cYou don't have permission to use this command."));
            return;
        }

        Player sender = Bukkit.getPlayer(request.senderId);
        if (sender == null) {
            removeRequest(request);
            recipient.sendMessage(message("&cThe requester is no longer online."));
            return;
        }

        Player teleporter = request.type == RequestType.TPA ? sender : recipient;
        Player destination = request.type == RequestType.TPA ? recipient : sender;
        if (warmups.containsKey(teleporter.getUniqueId())) {
            recipient.sendMessage(message("&cThat player already has a teleport in progress."));
            return;
        }

        removeRequest(request);
        recipient.sendMessage(message("&aTeleport request accepted."));
        teleporter.sendMessage(message("&eStay still for 5 seconds to teleport."));
        if (request.type == RequestType.TPA) {
            sender.sendMessage(message("&e" + recipient.getName() + " accepted your teleport request."));
        } else {
            sender.sendMessage(message("&e" + recipient.getName() + " accepted your teleport here request."));
        }
        startWarmup(teleporter, destination);
    }

    public void deny(Player recipient, RequestType commandType) {
        Request request = requestsByRecipient.get(recipient.getUniqueId());
        if (request == null) {
            recipient.sendMessage(message("&cYou don't have a pending teleport request."));
            return;
        }
        if (request.type != commandType) {
            recipient.sendMessage(message("&cThis is a " + request.type.displayName + ". Use /" + request.type.command + " deny."));
            return;
        }
        if (!recipient.hasPermission(permissionFor(commandType))) {
            recipient.sendMessage(message("&cYou don't have permission to use this command."));
            return;
        }

        removeRequest(request);
        recipient.sendMessage(message("&eTeleport request denied."));
        Player sender = Bukkit.getPlayer(request.senderId);
        if (sender != null) {
            sender.sendMessage(message("&c" + recipient.getName() + " denied your teleport request."));
        }
    }

    public void handleMovement(Player player, Location currentLocation) {
        Warmup warmup = warmups.get(player.getUniqueId());
        if (warmup != null && isSignificantMovement(warmup.startingLocation, currentLocation)) {
            cancelWarmup(player, CancelReason.MOVED);
        }
    }

    private boolean isSignificantMovement(Location startingLocation, Location currentLocation) {
        if (startingLocation.getWorld() == null || currentLocation.getWorld() == null
                || !startingLocation.getWorld().getUID().equals(currentLocation.getWorld().getUID())) {
            return true;
        }
        double dx = currentLocation.getX() - startingLocation.getX();
        double dy = currentLocation.getY() - startingLocation.getY();
        double dz = currentLocation.getZ() - startingLocation.getZ();
        return dx * dx + dy * dy + dz * dz > MOVEMENT_THRESHOLD_SQUARED;
    }

    public void cancelWarmup(Player player, CancelReason reason) {
        Warmup warmup = warmups.get(player.getUniqueId());
        if (warmup == null) {
            return;
        }
        removeWarmup(warmup);
        String cancellationMessage = reason == CancelReason.MOVED
                ? "&cTeleport cancelled because you moved."
                : "&cTeleport cancelled because you took damage.";
        player.sendMessage(message(cancellationMessage));
        player.sendActionBar(message("&cTeleport cancelled."));
    }

    public void handleQuit(Player player) {
        UUID playerId = player.getUniqueId();
        Request incoming = requestsByRecipient.get(playerId);
        if (incoming != null) {
            removeRequest(incoming);
        }
        Request outgoing = requestsBySender.get(playerId);
        if (outgoing != null) {
            removeRequest(outgoing);
        }
        Cooldown cooldown = cooldowns.remove(playerId);
        if (cooldown != null) {
            cooldown.task.cancel();
        }
        Warmup ownWarmup = warmups.get(playerId);
        if (ownWarmup != null) {
            removeWarmup(ownWarmup);
        }
        for (Warmup warmup : warmups.values().toArray(Warmup[]::new)) {
            if (warmup.destinationId.equals(playerId)) {
                removeWarmup(warmup);
                Player teleporter = Bukkit.getPlayer(warmup.teleporterId);
                if (teleporter != null) {
                    teleporter.sendMessage(message("&cTeleport cancelled because the destination left the server."));
                    teleporter.sendActionBar(message("&cTeleport cancelled."));
                }
            }
        }
    }

    public void clear() {
        for (Request request : requestsByRecipient.values().toArray(Request[]::new)) {
            removeRequest(request);
        }
        for (Warmup warmup : warmups.values().toArray(Warmup[]::new)) {
            removeWarmup(warmup);
        }
        for (Cooldown cooldown : cooldowns.values().toArray(Cooldown[]::new)) {
            cooldown.task.cancel();
        }
        cooldowns.clear();
    }

    public static Component message(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    private boolean hasCooldown(UUID playerId) {
        return cooldowns.containsKey(playerId);
    }

    private String permissionFor(RequestType type) {
        return type == RequestType.TPA ? "tpa.use" : "tpa.tpahere";
    }

    private void expireRequest(Request request) {
        if (requestsByRecipient.get(request.recipientId) != request) {
            return;
        }
        removeRequest(request);
        Player sender = Bukkit.getPlayer(request.senderId);
        Player recipient = Bukkit.getPlayer(request.recipientId);
        if (sender != null) {
            sender.sendMessage(message("&cYour teleport request expired."));
        }
        if (recipient != null) {
            recipient.sendMessage(message("&cThe teleport request expired."));
        }
    }

    private void removeRequest(Request request) {
        requestsByRecipient.remove(request.recipientId, request);
        requestsBySender.remove(request.senderId, request);
        if (request.expirationTask != null) {
            request.expirationTask.cancel();
        }
    }

    private void startWarmup(Player teleporter, Player destination) {
        Warmup warmup = new Warmup(teleporter.getUniqueId(), destination.getUniqueId(), teleporter.getLocation());
        warmups.put(warmup.teleporterId, warmup);
        showWarmupSecond(teleporter, WARMUP_DURATION_SECONDS);
        warmup.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> advanceWarmup(warmup), 20L, 20L);
    }

    private void advanceWarmup(Warmup warmup) {
        if (warmups.get(warmup.teleporterId) != warmup) {
            warmup.task.cancel();
            return;
        }
        if (warmup.remainingSeconds > 1) {
            warmup.remainingSeconds--;
            Player teleporter = Bukkit.getPlayer(warmup.teleporterId);
            if (teleporter == null) {
                removeWarmup(warmup);
                return;
            }
            showWarmupSecond(teleporter, warmup.remainingSeconds);
            return;
        }

        removeWarmup(warmup);
        Player teleporter = Bukkit.getPlayer(warmup.teleporterId);
        Player destination = Bukkit.getPlayer(warmup.destinationId);
        if (teleporter == null) {
            return;
        }
        if (destination == null) {
            teleporter.sendMessage(message("&cTeleport cancelled because the destination left the server."));
            teleporter.sendActionBar(message("&cTeleport cancelled."));
            return;
        }

        teleporter.sendActionBar(message("&aTeleporting..."));
        teleporter.playSound(teleporter.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.2f);
        boolean teleported = teleporter.teleport(destination.getLocation());
        if (!teleported) {
            teleporter.sendMessage(message("&cTeleport failed."));
            teleporter.sendActionBar(message("&cTeleport failed."));
            return;
        }
        teleporter.sendMessage(message("&aTeleported successfully."));
        startCooldown(teleporter);
    }

    private void showWarmupSecond(Player player, int seconds) {
        String unit = seconds == 1 ? "second" : "seconds";
        player.sendActionBar(message("&eTeleporting in &6" + seconds + " &e" + unit + "..."));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.45f, 1.2f);
    }

    private void startCooldown(Player player) {
        UUID playerId = player.getUniqueId();
        Cooldown previous = cooldowns.remove(playerId);
        if (previous != null) {
            previous.task.cancel();
        }
        Cooldown cooldown = new Cooldown();
        cooldowns.put(playerId, cooldown);
        player.sendActionBar(message("&eTPA Cooldown: &625s"));
        cooldown.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> advanceCooldown(playerId, cooldown), 20L, 20L);
    }

    private void advanceCooldown(UUID playerId, Cooldown cooldown) {
        if (cooldowns.get(playerId) != cooldown) {
            cooldown.task.cancel();
            return;
        }
        cooldown.remainingSeconds--;
        Player player = Bukkit.getPlayer(playerId);
        if (cooldown.remainingSeconds <= 0) {
            cooldowns.remove(playerId, cooldown);
            cooldown.task.cancel();
            if (player != null) {
                player.sendActionBar(message("&aYou can send another teleport request!"));
            }
            return;
        }
        if (player != null) {
            player.sendActionBar(message("&eTPA Cooldown: &6" + cooldown.remainingSeconds + "s"));
        }
    }

    private void removeWarmup(Warmup warmup) {
        if (warmups.remove(warmup.teleporterId, warmup) && warmup.task != null) {
            warmup.task.cancel();
        }
    }

    public enum RequestType {
        TPA("tpa", "normal TPA"),
        TPA_HERE("tpahere", "TPA Here");

        private final String command;
        private final String displayName;

        RequestType(String command, String displayName) {
            this.command = command;
            this.displayName = displayName;
        }
    }

    public enum CancelReason {
        MOVED,
        DAMAGED
    }

    private static final class Request {
        private final UUID senderId;
        private final UUID recipientId;
        private final RequestType type;
        private BukkitTask expirationTask;

        private Request(UUID senderId, UUID recipientId, RequestType type) {
            this.senderId = senderId;
            this.recipientId = recipientId;
            this.type = type;
        }
    }

    private static final class Warmup {
        private final UUID teleporterId;
        private final UUID destinationId;
        private final Location startingLocation;
        private int remainingSeconds = WARMUP_DURATION_SECONDS;
        private BukkitTask task;

        private Warmup(UUID teleporterId, UUID destinationId, Location startingLocation) {
            this.teleporterId = teleporterId;
            this.destinationId = destinationId;
            this.startingLocation = startingLocation;
        }
    }

    private static final class Cooldown {
        private int remainingSeconds = COOLDOWN_DURATION_SECONDS;
        private BukkitTask task;
    }
}
