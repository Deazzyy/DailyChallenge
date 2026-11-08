package pl.dailychallenge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

public final class ChallengeManager {

    public enum Type {
        TYPE(true), MATH(true), SCRAMBLE(true), PLACE(false), BREAK(false), KILL(false);

        public final boolean chat;

        Type(boolean chat) {
            this.chat = chat;
        }
    }

    public static final class Challenge {
        public final Type type;
        public final String answer;       // dla typow chatowych
        public final Material material;   // PLACE / BREAK
        public final EntityType entity;   // KILL
        public final Component target;
        public Component description = Component.empty();

        Challenge(Type type, String answer, Material material, EntityType entity, Component target) {
            this.type = type;
            this.answer = answer;
            this.material = material;
            this.entity = entity;
            this.target = target;
        }
    }

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final DailyChallengePlugin plugin;
    private final Random random = new Random();
    private final File dataFile;

    private volatile Challenge active;
    private BukkitTask ticker;
    private BukkitTask timeoutTask;

    private ZoneId zone;
    private LocalDate scheduleDate;
    private final List<LocalTime> schedule = new ArrayList<>();
    private final Set<Integer> fired = new HashSet<>();

    public ChallengeManager(DailyChallengePlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        reload();
    }

    // ------------------------------------------------------------------ lifecycle

    public void reload() {
        try {
            zone = ZoneId.of(plugin.getConfig().getString("timezone", "Europe/Warsaw"));
        } catch (Exception e) {
            plugin.getLogger().warning("Zla strefa czasowa w configu, uzywam Europe/Warsaw.");
            zone = ZoneId.of("Europe/Warsaw");
        }
    }

    public void start() {
        loadData();
        // sprawdzanie harmonogramu co 10 sekund
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 200L);
    }

    public void stop() {
        if (ticker != null) ticker.cancel();
        if (timeoutTask != null) timeoutTask.cancel();
        active = null;
        saveData();
    }

    public Challenge getActive() {
        return active;
    }

    // ------------------------------------------------------------------ schedule

    private void tick() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        if (!now.toLocalDate().equals(scheduleDate)) {
            generateSchedule();
        }
        LocalTime t = now.toLocalTime();
        for (int i = 0; i < schedule.size(); i++) {
            if (fired.contains(i)) continue;
            LocalTime planned = schedule.get(i);
            if (t.isBefore(planned)) continue;

            fired.add(i);
            saveData();

            long lateMinutes = Duration.between(planned, t).toMinutes();
            if (lateMinutes > 5) continue; // np. serwer byl wylaczony - pomijamy
            if (active != null) continue;
            if (Bukkit.getOnlinePlayers().size() < plugin.getConfig().getInt("min-players", 1)) continue;

            startChallenge(null);
            break;
        }
    }

    private void generateSchedule() {
        FileConfiguration c = plugin.getConfig();
        int from = LocalTime.parse(c.getString("start-time", "10:00")).toSecondOfDay() / 60;
        int to = LocalTime.parse(c.getString("end-time", "23:59")).toSecondOfDay() / 60;
        int count = Math.max(0, c.getInt("challenges-per-day", 5));
        int gap = Math.max(0, c.getInt("min-gap-minutes", 30));

        ZonedDateTime now = ZonedDateTime.now(zone);
        scheduleDate = now.toLocalDate();
        // jesli generujemy w srodku dnia (np. pierwszy start), losujemy tylko z reszty dnia
        from = Math.max(from, now.toLocalTime().toSecondOfDay() / 60 + 1);

        schedule.clear();
        fired.clear();

        if (from < to && count > 0) {
            List<Integer> picked = new ArrayList<>();
            for (int attempt = 0; attempt < 3000; attempt++) {
                picked.clear();
                for (int i = 0; i < count; i++) picked.add(from + random.nextInt(to - from + 1));
                Collections.sort(picked);
                boolean ok = true;
                for (int i = 1; i < picked.size(); i++) {
                    if (picked.get(i) - picked.get(i - 1) < gap) {
                        ok = false;
                        break;
                    }
                }
                if (ok) break;
            }
            for (int m : picked) schedule.add(LocalTime.of(m / 60, m % 60));
        }
        plugin.getLogger().info("Harmonogram na " + scheduleDate + ": " + describeSchedule());
        saveData();
    }

    public String describeSchedule() {
        if (schedule.isEmpty()) return "(brak)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < schedule.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(schedule.get(i));
            if (fired.contains(i)) sb.append("✔");
        }
        return sb.toString();
    }

    private void loadData() {
        if (dataFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
            String date = y.getString("date");
            if (date != null && date.equals(LocalDate.now(zone).toString())) {
                scheduleDate = LocalDate.parse(date);
                schedule.clear();
                fired.clear();
                for (String s : y.getStringList("times")) schedule.add(LocalTime.parse(s));
                fired.addAll(y.getIntegerList("fired"));
                return;
            }
        }
        generateSchedule();
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("date", scheduleDate == null ? null : scheduleDate.toString());
        List<String> times = new ArrayList<>();
        for (LocalTime t : schedule) times.add(t.toString());
        y.set("times", times);
        y.set("fired", new ArrayList<>(fired));
        try {
            plugin.getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie udalo sie zapisac data.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ challenge creation

    /** @return false, jesli jakis challenge juz trwa */
    public boolean startChallenge(Type forced) {
        if (active != null) return false;

        Type type = forced != null ? forced : pickType();
        Challenge ch = create(type);
        if (ch == null) {
            ch = create(Type.TYPE); // awaryjnie
        }

        FileConfiguration c = plugin.getConfig();
        ch.description = fmt(c.getString("messages.types." + ch.type.name(), "{target}"),
                Map.of("{target}", ch.target));
        active = ch;

        int timeout = Math.max(10, c.getInt("timeout-seconds", 300));
        String timeText = timeout >= 60 ? (timeout / 60) + " min" : timeout + " s";
        Map<String, Component> reps = Map.of(
                "{description}", ch.description,
                "{min}", Component.text(c.getInt("reward.min", 100)),
                "{max}", Component.text(c.getInt("reward.max", 300)),
                "{time}", Component.text(timeText));

        for (String line : c.getStringList("messages.broadcast")) {
            Bukkit.broadcast(fmt(line, reps));
        }

        if (c.getBoolean("show-title", true)) {
            Title title = Title.title(
                    LEGACY.deserialize(c.getString("messages.title", "&6&lCODZIENNY CHALLENGE")),
                    ch.description,
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(600)));
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(title);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            }
        }

        final Challenge started = ch;
        timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (active == started) {
                active = null;
                Bukkit.broadcast(fmt(prefix() + c.getString("messages.timeout", ""), Map.of()));
            }
        }, timeout * 20L);
        return true;
    }

    private Type pickType() {
        List<Type> enabled = new ArrayList<>();
        for (String s : plugin.getConfig().getStringList("enabled-types")) {
            try {
                enabled.add(Type.valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Nieznany typ w enabled-types: " + s);
            }
        }
        if (enabled.isEmpty()) enabled.add(Type.TYPE);
        return enabled.get(random.nextInt(enabled.size()));
    }

    private Challenge create(Type type) {
        FileConfiguration c = plugin.getConfig();
        switch (type) {
            case TYPE -> {
                String chars = c.getString("type-chars", "abcdefghjkmnpqrstuvwxyz23456789");
                int len = Math.max(3, c.getInt("type-length", 8));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < len; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
                String code = sb.toString();
                return new Challenge(type, code, null, null,
                        Component.text(code, NamedTextColor.YELLOW, TextDecoration.BOLD));
            }
            case MATH -> {
                int a, b, result;
                String expr;
                switch (random.nextInt(3)) {
                    case 0 -> {
                        a = 100 + random.nextInt(900);
                        b = 100 + random.nextInt(900);
                        result = a + b;
                        expr = a + " + " + b;
                    }
                    case 1 -> {
                        a = 300 + random.nextInt(700);
                        b = 10 + random.nextInt(290);
                        result = a - b;
                        expr = a + " - " + b;
                    }
                    default -> {
                        a = 6 + random.nextInt(14);
                        b = 6 + random.nextInt(14);
                        result = a * b;
                        expr = a + " × " + b;
                    }
                }
                return new Challenge(type, String.valueOf(result), null, null,
                        Component.text(expr + " = ?", NamedTextColor.YELLOW, TextDecoration.BOLD));
            }
            case SCRAMBLE -> {
                List<String> words = c.getStringList("words");
                if (words.isEmpty()) return null;
                String word = words.get(random.nextInt(words.size())).toLowerCase(Locale.ROOT);
                String scrambled = word;
                for (int i = 0; i < 30 && scrambled.equals(word) && word.length() > 1; i++) {
                    List<Character> chars = new ArrayList<>();
                    for (char ch : word.toCharArray()) chars.add(ch);
                    Collections.shuffle(chars, random);
                    StringBuilder sb = new StringBuilder();
                    for (char ch : chars) sb.append(ch);
                    scrambled = sb.toString();
                }
                return new Challenge(type, word, null, null,
                        Component.text(scrambled, NamedTextColor.YELLOW, TextDecoration.BOLD));
            }
            case PLACE, BREAK -> {
                List<Material> mats = new ArrayList<>();
                for (String s : c.getStringList(type == Type.PLACE ? "place-blocks" : "break-blocks")) {
                    Material m = Material.matchMaterial(s);
                    if (m != null && m.isBlock()) mats.add(m);
                    else plugin.getLogger().warning("Nieznany blok w configu: " + s);
                }
                if (mats.isEmpty()) return null;
                Material m = mats.get(random.nextInt(mats.size()));
                return new Challenge(type, null, m, null,
                        Component.translatable(m.translationKey(), NamedTextColor.YELLOW, TextDecoration.BOLD));
            }
            case KILL -> {
                List<EntityType> types = new ArrayList<>();
                for (String s : c.getStringList("kill-mobs")) {
                    try {
                        types.add(EntityType.valueOf(s.toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Nieznany mob w configu: " + s);
                    }
                }
                if (types.isEmpty()) return null;
                EntityType et = types.get(random.nextInt(types.size()));
                return new Challenge(type, null, null, et,
                        Component.translatable(et.translationKey(), NamedTextColor.YELLOW, TextDecoration.BOLD));
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ finishing

    /** Wywolywac TYLKO z glownego watku. */
    public void win(Player player, Challenge challenge) {
        if (active != challenge) return; // ktos byl szybszy / challenge wygasl
        active = null;
        if (timeoutTask != null) timeoutTask.cancel();

        FileConfiguration c = plugin.getConfig();
        int min = c.getInt("reward.min", 100);
        int max = Math.max(min, c.getInt("reward.max", 300));
        int reward = min + random.nextInt(max - min + 1);

        Economy eco = plugin.getEconomy();
        if (eco != null) {
            eco.depositPlayer(player, reward);
        } else {
            player.sendMessage(fmt(c.getString("messages.no-economy", ""), Map.of()));
            plugin.getLogger().severe("Brak ekonomii Vault - nie wyplacono " + reward + " dla " + player.getName());
        }

        Map<String, Component> reps = Map.of(
                "{player}", Component.text(player.getName()),
                "{reward}", Component.text(reward));
        for (String line : c.getStringList("messages.winner")) {
            Bukkit.broadcast(fmt(line, reps));
        }
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
    }

    public void cancelActive() {
        active = null;
        if (timeoutTask != null) timeoutTask.cancel();
    }

    // ------------------------------------------------------------------ helpers

    private String prefix() {
        return plugin.getConfig().getString("messages.prefix", "");
    }

    public Component fmt(String raw, Map<String, Component> reps) {
        Component comp = LEGACY.deserialize(raw);
        for (Map.Entry<String, Component> e : reps.entrySet()) {
            comp = comp.replaceText(TextReplacementConfig.builder()
                    .matchLiteral(e.getKey()).replacement(e.getValue()).build());
        }
        return comp;
    }
}
