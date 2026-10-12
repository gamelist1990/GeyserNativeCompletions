package com.pexserver.completions;

import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.command.*;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FiniteArgumentHintsTest {
    private static CommandParamData option(String name, CommandEnumData domain) {
        CommandParamData param = new CommandParamData();
        param.setName(name);
        param.setEnumData(domain);
        return param;
    }
    private static CommandData command(String name, CommandParamData... arguments) {
        return new CommandData(name, "Finite choices", Set.of(CommandData.Flag.NOT_CHEAT),
                CommandPermission.GAME_DIRECTORS, null, List.of(),
                new CommandOverloadData[]{new CommandOverloadData(false, arguments)});
    }
    private static CommandParamData first(CommandData command) {
        return command.getOverloads()[0].getOverloads()[0];
    }
    @Test void recognizesBooleanAndOtherFiniteArgumentDomainsForUnrelatedCommands() {
        CommandData bossbar = command("bossbar", option("visible",
                PacketComposer.enumData("bool", List.of("true", "false"), false)));
        CommandData random = command("random", option("includeWorldSeed",
                PacketComposer.enumData("bool", List.of("true", "false"), false)));
        CommandData custom = command("myplugin:settings", option("difficulty",
                PacketComposer.enumData("difficulty", List.of("easy", "normal", "hard"), false)));
        CommandData changedBossbar = JavaArgumentHints.knownChoices(bossbar);
        CommandData changedRandom = JavaArgumentHints.knownChoices(random);
        CommandData changedCustom = JavaArgumentHints.knownChoices(custom);
        for (CommandData changed : List.of(changedBossbar, changedRandom, changedCustom)) {
            assertTrue(first(changed).getEnumData().isSoft());
            assertEquals(changed, JavaArgumentHints.knownChoices(changed));
        }
        assertEquals(first(changedBossbar).getEnumData().getName(), first(changedRandom).getEnumData().getName());
        assertNotEquals(first(changedRandom).getEnumData().getName(), first(changedCustom).getEnumData().getName());
        assertEquals(Set.of("easy", "normal", "hard"), first(changedCustom).getEnumData().getValues().keySet());
        assertFalse(first(bossbar).getEnumData().isSoft());
    }
    @Test void staysWithHardEnumsForCommandLiteralsConstraintsAndLargeRegistries() {
        CommandParamData literal = option("give", PacketComposer.enumData("give", List.of("give", "clear"), false));
        CommandParamData collision = option("if", PacketComposer.enumData("if_arg3_abc", List.of("if", "unless"), false));
        CommandParamData singleton = option("one", PacketComposer.enumData("choices", List.of("only"), false));
        Map<String, Set<CommandEnumConstraint>> limited = new LinkedHashMap<>();
        limited.put("a", Set.of(CommandEnumConstraint.ALLOW_ALIASES));
        limited.put("b", Set.of());
        CommandParamData constrained = option("value", new CommandEnumData("options", limited, false));
        CommandParamData suppressed = option("suppressed", PacketComposer.enumData("flags", List.of("on", "off"), false));
        suppressed.getOptions().add(CommandParamOption.SUPPRESS_ENUM_AUTOCOMPLETION);
        List<String> large = new ArrayList<>();
        for (int i = 0; i < 65; i++) large.add("value_" + i);
        CommandParamData registry = option("registry", PacketComposer.enumData("registry", large, false));
        for (CommandParamData arg : List.of(literal, collision, singleton, constrained, suppressed, registry)) {
            CommandData original = command("plugin:complex", arg);
            assertSame(original, JavaArgumentHints.knownChoices(original));
        }
    }
    @Test void updatesDomainIdsWhenOptionsChangeAndRoundTripsAcrossBedrockCodec() {
        CommandData before = command("plugin:custom", option("setting",
                PacketComposer.enumData("setting_types", List.of("red", "blue"), false)));
        CommandData after = command("plugin:custom", option("setting",
                PacketComposer.enumData("setting_types", List.of("red", "blue", "green"), false)));
        String firstId = first(JavaArgumentHints.knownChoices(before)).getEnumData().getName();
        String nextId = first(JavaArgumentHints.knownChoices(after)).getEnumData().getName();
        assertNotEquals(firstId, nextId);
        AvailableCommandsPacket packet = new AvailableCommandsPacket();
        packet.getCommands().add(after);
        var patched = PacketComposer.patch(packet, Map.of());
        var codec = Bedrock_v2193.CODEC;
        var serializer = codec.getPacketDefinition(AvailableCommandsPacket.class).getSerializer();
        var buffer = Unpooled.buffer();
        try {
            serializer.serialize(buffer, codec.createHelper(), patched);
            var decoded = new AvailableCommandsPacket();
            serializer.deserialize(buffer, codec.createHelper(), decoded);
            assertEquals(nextId, first(decoded.getCommands().getFirst()).getEnumData().getName());
            assertTrue(first(decoded.getCommands().getFirst()).getEnumData().isSoft());
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }
}
