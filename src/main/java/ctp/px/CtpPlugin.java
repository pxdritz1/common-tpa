package ctp.px;

import com.mojang.brigadier.arguments.StringArgumentType;
import ctp.px.listeners.PlayerListener;
import ctp.px.managers.TpaManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class CtpPlugin extends JavaPlugin {
    private TpaManager tpaManager;

    @Override
    public void onEnable() {
        tpaManager = new TpaManager(this);
        getServer().getPluginManager().registerEvents(new PlayerListener(tpaManager), this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(
                    Commands.literal("tpa")
                            .then(Commands.literal("accept")
                                    .executes(context -> runForPlayer(context.getSource(), player -> tpaManager.accept(player, TpaManager.RequestType.TPA))))
                            .then(Commands.literal("deny")
                                    .executes(context -> runForPlayer(context.getSource(), player -> tpaManager.deny(player, TpaManager.RequestType.TPA))))
                            .then(Commands.argument("player", StringArgumentType.word())
                                    .executes(context -> runForPlayer(context.getSource(), player ->
                                            tpaManager.request(player, StringArgumentType.getString(context, "player"), TpaManager.RequestType.TPA))))
                            .build()
            );
            event.registrar().register(
                    Commands.literal("tpahere")
                            .then(Commands.literal("accept")
                                    .executes(context -> runForPlayer(context.getSource(), player -> tpaManager.accept(player, TpaManager.RequestType.TPA_HERE))))
                            .then(Commands.literal("deny")
                                    .executes(context -> runForPlayer(context.getSource(), player -> tpaManager.deny(player, TpaManager.RequestType.TPA_HERE))))
                            .then(Commands.argument("player", StringArgumentType.word())
                                    .executes(context -> runForPlayer(context.getSource(), player ->
                                            tpaManager.request(player, StringArgumentType.getString(context, "player"), TpaManager.RequestType.TPA_HERE))))
                            .build()
            );
        });
    }

    @Override
    public void onDisable() {
        if (tpaManager != null) {
            tpaManager.clear();
        }
    }

    private int runForPlayer(CommandSourceStack source, PlayerCommand action) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(TpaManager.message("&cOnly players can use this command."));
            return 0;
        }
        action.run(player);
        return 1;
    }

    @FunctionalInterface
    private interface PlayerCommand {
        void run(Player player);
    }
}
