package ctp.px.listeners;

import ctp.px.managers.TpaManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerListener implements Listener {
    private final TpaManager tpaManager;

    public PlayerListener(TpaManager tpaManager) {
        this.tpaManager = tpaManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() != null) {
            tpaManager.handleMovement(event.getPlayer(), event.getTo());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            tpaManager.cancelWarmup(player, TpaManager.CancelReason.DAMAGED);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        tpaManager.handleQuit(event.getPlayer());
    }
}
