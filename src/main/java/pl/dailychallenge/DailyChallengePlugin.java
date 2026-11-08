package pl.dailychallenge;

import net.milkbowl.vault.economy.Economy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class DailyChallengePlugin extends JavaPlugin implements TabExecutor {

    private ChallengeManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new ChallengeManager(this);
        getServer().getPluginManager().registerEvents(new ChallengeListener(this, manager), this);
        var cmd = getCommand("challenge");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        manager.start();
        getLogger().info("DailyChallenge wlaczony.");
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.stop();
    }

    /** Ekonomia pobierana przy kazdej wyplacie, wiec kolejnosc ladowania pluginow nie ma znaczenia. */
    public Economy getEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("info")) {
            ChallengeManager.Challenge ch = manager.getActive();
            if (ch == null) {
                sender.sendMessage(manager.fmt(getConfig().getString("messages.no-active", ""), java.util.Map.of()));
            } else {
                sender.sendMessage(manager.fmt(getConfig().getString("messages.active", ""), java.util.Map.of())
                        .append(ch.description));
            }
            return true;
        }

        if (!sender.hasPermission("dailychallenge.admin")) {
            sender.sendMessage(manager.fmt(getConfig().getString("messages.no-permission", ""), java.util.Map.of()));
            return true;
        }

        switch (sub) {
            case "start" -> {
                ChallengeManager.Type forced = null;
                if (args.length > 1) {
                    try {
                        forced = ChallengeManager.Type.valueOf(args[1].toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        sender.sendMessage(Component.text("Nieznany typ. Dostepne: "
                                + Arrays.toString(ChallengeManager.Type.values()), NamedTextColor.RED));
                        return true;
                    }
                }
                if (!manager.startChallenge(forced)) {
                    sender.sendMessage(Component.text("Challenge juz trwa (uzyj /challenge stop).", NamedTextColor.RED));
                }
            }
            case "stop" -> {
                manager.cancelActive();
                sender.sendMessage(Component.text("Challenge zatrzymany.", NamedTextColor.YELLOW));
            }
            case "schedule" -> {
                sender.sendMessage(Component.text("Dzisiejszy harmonogram: " + manager.describeSchedule(),
                        NamedTextColor.YELLOW));
            }
            case "reload" -> {
                reloadConfig();
                manager.reload();
                sender.sendMessage(Component.text("Przeladowano config.", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text(
                    "Uzycie: /challenge [info|start [typ]|stop|schedule|reload]", NamedTextColor.GRAY));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("info");
            if (sender.hasPermission("dailychallenge.admin")) out.addAll(List.of("start", "stop", "schedule", "reload"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("start")
                && sender.hasPermission("dailychallenge.admin")) {
            for (ChallengeManager.Type t : ChallengeManager.Type.values()) out.add(t.name());
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
