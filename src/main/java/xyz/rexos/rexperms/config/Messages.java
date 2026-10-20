package xyz.rexos.rexperms.config;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.rexos.rexperms.util.Text;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * messages.yml handler. Every message is MiniMessage formatted.
 * Keys missing from the user's file are taken from the jar's default and
 * written back, so updates never lose new messages.
 *
 * Messages are sent through adventure-platform, which renders hover/click
 * components and RGB colours on 1.16+, and downsamples gracefully on 1.8–1.15.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private BukkitAudiences audiences;
    private YamlConfiguration yaml;
    private YamlConfiguration defaults;
    private String prefix = "";

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        if (audiences == null) audiences = BukkitAudiences.create(plugin);
    }

    public void disable() {
        if (audiences != null) {
            audiences.close();
            audiences = null;
        }
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        yaml = loadUtf8(file);

        InputStream in = plugin.getResource("messages.yml");
        if (in != null) {
            try {
                Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
                defaults = YamlConfiguration.loadConfiguration(reader);
                reader.close();
                boolean changed = false;
                for (String key : defaults.getKeys(true)) {
                    if (!defaults.isConfigurationSection(key) && !yaml.contains(key)) {
                        yaml.set(key, defaults.get(key));
                        changed = true;
                    }
                }
                if (changed) yaml.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not update messages.yml: " + e.getMessage());
            }
        }
        prefix = yaml.getString("prefix", "");
    }

    /** Reads as UTF-8 regardless of the platform charset (old servers default to the system one). */
    private YamlConfiguration loadUtf8(File file) {
        try {
            Reader reader = new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8);
            try {
                return YamlConfiguration.loadConfiguration(reader);
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read messages.yml: " + e.getMessage());
            return new YamlConfiguration();
        }
    }

    public String raw(String key) {
        String s = yaml.getString(key);
        if (s == null && defaults != null) s = defaults.getString(key);
        return s == null ? "<red>[missing message: " + key + "]" : s;
    }

    public List<String> rawList(String key) {
        List<String> l = yaml.getStringList(key);
        if (l.isEmpty() && defaults != null) l = defaults.getStringList(key);
        return l;
    }

    /** Component without the prefix. */
    public Component component(String key, TagResolver... resolvers) {
        return MM.deserialize(raw(key), TagResolver.resolver(resolvers));
    }

    /** Component with the prefix. */
    public Component prefixed(String key, TagResolver... resolvers) {
        return MM.deserialize(prefix + raw(key), TagResolver.resolver(resolvers));
    }

    /** '§' text for places that only accept strings (kick screens). */
    public String legacy(String key, TagResolver... resolvers) {
        return Text.toLegacy(component(key, resolvers));
    }

    public void send(CommandSender to, String key, TagResolver... resolvers) {
        send(to, prefixed(key, resolvers));
    }

    /** Sends without the prefix (list lines, headers). */
    public void sendPlain(CommandSender to, String key, TagResolver... resolvers) {
        send(to, component(key, resolvers));
    }

    public void sendList(CommandSender to, String key, TagResolver... resolvers) {
        TagResolver all = TagResolver.resolver(resolvers);
        for (String line : rawList(key)) {
            send(to, MM.deserialize(line, all));
        }
    }

    public void send(CommandSender to, Component component) {
        if (audiences != null) {
            audiences.sender(to).sendMessage(component);
        } else {
            to.sendMessage(Text.toLegacy(component));
        }
    }

    // ------------------------------------------------------------------ shortcuts

    /** Placeholder for user input: tags inside it are NOT interpreted. */
    public static TagResolver text(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    public static TagResolver comp(String key, Component value) {
        return Placeholder.component(key, value);
    }

    public static MiniMessage mini() {
        return MM;
    }
}
