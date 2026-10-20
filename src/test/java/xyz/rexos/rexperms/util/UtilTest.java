package xyz.rexos.rexperms.util;

import org.junit.jupiter.api.Test;
import xyz.rexos.rexperms.command.ArgsAccess;
import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.model.NodeType;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class UtilTest {

    @Test
    void durations() {
        assertEquals(30, Durations.parseSeconds("30s"));
        assertEquals(90 * 60, Durations.parseSeconds("1h30m"));
        assertEquals(7 * 86_400, Durations.parseSeconds("7d"));
        assertEquals(14 * 86_400, Durations.parseSeconds("2w"));
        assertEquals(30L * 86_400, Durations.parseSeconds("1mo"));
        assertEquals(365L * 86_400, Durations.parseSeconds("1y"));
        assertEquals(-1, Durations.parseSeconds("abc"));
        assertEquals(-1, Durations.parseSeconds("10x"));
        assertEquals(-1, Durations.parseSeconds("0d"));
        assertEquals("1 day 2 hours", Durations.format(86_400 + 7_200));
        assertEquals("3 days 1 hour 1 minute", Durations.format(3 * 86_400 + 3_600 + 60 + 5));
        assertEquals("2d 3h", Durations.formatShort(2 * 86_400 + 3 * 3_600 + 59));
    }

    @Test
    void nodeTypesAndParsing() {
        assertEquals(NodeType.INHERITANCE, Node.inheritance("VIP", ContextSet.EMPTY).type());
        assertEquals("group.vip", Node.inheritance("VIP", ContextSet.EMPTY).key());

        Node prefix = Node.prefix(100, "&c[Admin. Team] ", ContextSet.EMPTY);
        assertEquals(NodeType.PREFIX, prefix.type());
        assertEquals(100, prefix.chatPriority());
        assertEquals("&c[Admin. Team] ", prefix.chatValue(), "prefix text, including the trailing space, is kept");

        Node meta = Node.meta("Homes", "Five.Houses", ContextSet.EMPTY);
        assertEquals("homes", meta.metaKey());
        assertEquals("Five.Houses", meta.metaValue());

        assertEquals(NodeType.PERMISSION, NodeType.of("prefix.notanumber"));
        assertEquals(NodeType.PERMISSION, NodeType.of("weight"));
        assertEquals(NodeType.WEIGHT, NodeType.of("weight.50"));
        assertEquals("essentials.fly", Node.permission("Essentials.FLY", true, ContextSet.EMPTY).key());
    }

    @Test
    void contexts() {
        ContextSet c = ContextSet.parse("World=Survival,server=lobby");
        assertEquals("survival", c.get("world"));
        assertTrue(ContextSet.of("world", "survival").isSatisfiedBy(c));
        assertFalse(ContextSet.of("world", "nether").isSatisfiedBy(c));
        assertTrue(ContextSet.EMPTY.isSatisfiedBy(c));
        assertEquals(c, ContextSet.parse(c.serialize()));
        assertThrows(IllegalArgumentException.class, () -> ContextSet.parse("broken"));
    }

    @Test
    void legacyColors() {
        assertEquals("<reset><red>[A]", Text.legacyToMini("&c[A]"));
        assertEquals("<reset><#ff0000>X", Text.legacyToMini("&#ff0000X"));
        assertEquals("<reset><#ff0000>X", Text.legacyToMini("&x&f&f&0&0&0&0X"));
        assertEquals("<bold>B", Text.legacyToMini("&lB"));
        assertEquals("<red>plain", Text.legacyToMini("<red>plain"));
        assertEquals("a & b", Text.legacyToMini("a & b"));
    }

    @Test
    void legacyOutputForOldAndNewServers() {
        // Outside a server the version detector assumes 1.8: RGB is downsampled to the nearest colour
        assertEquals("§c[Admin] ", Text.legacy("&#ff5555[Admin] "));
        assertEquals("§6§l[VIP]", Text.legacy("&6&l[VIP]"));
        assertEquals("", Text.legacy(null));
    }

    @Test
    void argumentTokenizer() {
        assertEquals(Arrays.asList("meta", "setprefix", "100", "&c[Admin] ", "world=survival"),
                ArgsAccess.tokens("meta setprefix 100 \"&c[Admin] \" world=survival"));
        assertEquals(Arrays.asList("a", "say \"hi\""), ArgsAccess.tokens("a \"say \\\"hi\\\"\""));
        assertEquals(Collections.singletonList(""), ArgsAccess.tokens("\"\""));

        // A quoted key=value is not a context
        assertEquals(ContextSet.of("world", "survival"), ArgsAccess.context("set x \"a=b\" world=survival"));
    }
}
