package pl.dailychallenge;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;

public final class ChallengeListener implements Listener {

    private final DailyChallengePlugin plugin;
    private final ChallengeManager manager;

    public ChallengeListener(DailyChallengePlugin plugin, ChallengeManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    private boolean eligible(Player p) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.SPECTATOR) return false;
        return gm != GameMode.CREATIVE || plugin.getConfig().getBoolean("allow-creative", false);
    }

    // ---- chat: TYPE / MATH / SCRAMBLE (zdarzenie asynchroniczne -> wygrana na glownym watku)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        ChallengeManager.Challenge ch = manager.getActive();
        if (ch == null || !ch.type.chat) return;

        String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        boolean caseSensitive = plugin.getConfig().getBoolean("case-sensitive", false);
        boolean match = caseSensitive ? msg.equals(ch.answer) : msg.equalsIgnoreCase(ch.answer);
        if (!match) return;

        Player player = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> manager.win(player, ch));
    }

    // ---- PLACE
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        ChallengeManager.Challenge ch = manager.getActive();
        if (ch == null || ch.type != ChallengeManager.Type.PLACE) return;
        if (e.getBlockPlaced().getType() != ch.material) return;
        if (!eligible(e.getPlayer())) return;
        manager.win(e.getPlayer(), ch);
    }

    // ---- BREAK
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        ChallengeManager.Challenge ch = manager.getActive();
        if (ch == null || ch.type != ChallengeManager.Type.BREAK) return;
        if (e.getBlock().getType() != ch.material) return;
        if (!eligible(e.getPlayer())) return;
        manager.win(e.getPlayer(), ch);
    }

    // ---- KILL
    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        ChallengeManager.Challenge ch = manager.getActive();
        if (ch == null || ch.type != ChallengeManager.Type.KILL) return;
        if (e.getEntity().getType() != ch.entity) return;
        Player killer = e.getEntity().getKiller();
        if (killer == null || !eligible(killer)) return;
        manager.win(killer, ch);
    }
}
