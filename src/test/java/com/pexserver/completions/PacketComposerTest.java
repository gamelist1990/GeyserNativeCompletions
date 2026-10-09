package com.pexserver.completions;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.command.*;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static com.pexserver.completions.CompletionModel.*;

class PacketComposerTest {
    @Test void keepsLiteralEnumsOutOfTheBuiltinParserNamespaceEvenWithoutDuplicateEnums() {
        AvailableCommandsPacket packet = new AvailableCommandsPacket();
        for (String name : List.of("string", "int", "name", "block_state")) {
            CommandData command = original("literal_" + name, CommandParam.STRING);
            command.getOverloads()[0].getOverloads()[0].setEnumData(PacketComposer.enumData(name, List.of(name), false));
            packet.getCommands().add(command);
        }
        CommandData nativeCommand = original("gamemode", CommandParam.STRING); packet.getCommands().add(nativeCommand);
        var normalized = PacketComposer.uniqueHardEnums(packet);
        for (int i = 0; i < 4; i++) {
            var before = packet.getCommands().get(i).getOverloads()[0].getOverloads()[0].getEnumData();
            var after = normalized.getCommands().get(i).getOverloads()[0].getOverloads()[0].getEnumData();
            assertNotEquals(before.getName(), after.getName());
            assertEquals(before.getValues(), after.getValues());
        }
        assertSame(nativeCommand, normalized.getCommands().getLast());
        assertEquals(normalized, PacketComposer.uniqueHardEnums(normalized));
    }
    @Test void excludesServerHelpWithoutChangingNativeCommandsOrNamespacedHelp() {
        AvailableCommandsPacket packet = new AvailableCommandsPacket();
        CommandData give = original("give", CommandParam.TARGET);
        CommandData javaHelp = original("bukkit:help", CommandParam.STRING);
        CommandData help = original("help", CommandParam.STRING);
        CommandData emptyHelp = new CommandData("help", "", Set.of(CommandData.Flag.NOT_CHEAT),
                CommandPermission.ANY, PacketComposer.enumData("helpAliases", List.of("help"), false),
                List.of(), new CommandOverloadData[0]);
        packet.getCommands().addAll(List.of(give, help, javaHelp, emptyHelp));
        var compatible = PacketComposer.clientCompatible(packet);
        assertEquals(List.of(give, javaHelp), compatible.getCommands());
        assertSame(give, compatible.getCommands().getFirst());
        assertSame(javaHelp, compatible.getCommands().getLast());
        assertEquals(4, packet.getCommands().size());
        assertEquals(compatible, PacketComposer.clientCompatible(compatible));
    }
    @Test void retainsOtherHelpAliasesAndTheirArgumentsPermissionsAndConstraints() {
        var params = original("help", CommandParam.STRING).getOverloads();
        Map<String, Set<CommandEnumConstraint>> values = new LinkedHashMap<>();
        values.put("help", Set.of(CommandEnumConstraint.ALLOW_ALIASES));
        values.put("plugin:help", Set.of(CommandEnumConstraint.ALLOW_ALIASES));
        values.put("pluginhelp", Set.of());
        CommandData help = new CommandData("help", "Custom help", Set.of(CommandData.Flag.NOT_CHEAT),
                CommandPermission.GAME_DIRECTORS, new CommandEnumData("helpAliases", values, false), List.of(), params);
        AvailableCommandsPacket packet = new AvailableCommandsPacket(); packet.getCommands().add(help);
        CommandData safe = PacketComposer.clientCompatible(packet).getCommands().getFirst();
        assertEquals("plugin:help", safe.getName());
        assertEquals(Set.of("plugin:help", "pluginhelp"), safe.getAliases().getValues().keySet());
        assertEquals(values.get("plugin:help"), safe.getAliases().getValues().get("plugin:help"));
        assertEquals(help.getPermission(), safe.getPermission());
        assertEquals(help.getDescription(), safe.getDescription());
        assertEquals(help.getFlags(), safe.getFlags());
        assertSame(params, safe.getOverloads());
        assertTrue(help.getAliases().getValues().containsKey("help"));
        var aliased = new AvailableCommandsPacket();
        aliased.getCommands().add(new CommandData("pluginhelp", safe.getDescription(), safe.getFlags(), safe.getPermission(),
                help.getAliases(), safe.getSubcommands(), params));
        assertFalse(PacketComposer.clientCompatible(aliased).getCommands().getFirst().getAliases().getValues().containsKey("help"));
    }
    @Test void preventsHelpFromReturningInTheInitialOrGeneratedEncodedCommandPacket() {
        AvailableCommandsPacket original = baseline(); original.getCommands().add(original("help", CommandParam.STRING));
        AtomicReference<AvailableCommandsPacket> baseline = new AtomicReference<>();
        CommandPacketInterceptor hook = new CommandPacketInterceptor(() -> Map.of("home", model("base")), baseline::set, e -> fail(e));
        EmbeddedChannel channel = new EmbeddedChannel(hook);
        try {
            for (boolean generated : List.of(false, true)) {
                AvailableCommandsPacket input = generated ? PacketComposer.patch(original, Map.of("home", model("base"))) : original;
                if (generated) hook.markGenerated(input);
                channel.writeOutbound(BedrockPacketWrapper.create(0, 0, 0, input, null));
                BedrockPacketWrapper wrapper = channel.readOutbound();
                var buffer = Unpooled.buffer();
                try {
                    var codec = Bedrock_v2193.CODEC;
                    var serializer = codec.getPacketDefinition(AvailableCommandsPacket.class).getSerializer();
                    serializer.serialize(buffer, codec.createHelper(), (AvailableCommandsPacket) wrapper.getPacket());
                    var decoded = new AvailableCommandsPacket(); serializer.deserialize(buffer, codec.createHelper(), decoded);
                    assertEquals(List.of("home", "native"), decoded.getCommands().stream().map(CommandData::getName).toList());
                    assertTrue(decoded.getCommands().getFirst().getOverloads().length > 1);
                    assertEquals(original, baseline.get());
                    assertEquals(0, buffer.readableBytes());
                } finally { buffer.release(); wrapper.release(); }
            }
        } finally { channel.finishAndReleaseAll(); }
    }
    @Test void normalizesTheFirstPacketBeforeAnyCompletionModelsExist() {
        AvailableCommandsPacket original = new AvailableCommandsPacket();
        CommandData a = original("a", CommandParam.STRING);
        CommandData b = original("b", CommandParam.STRING);
        CommandParamData argument = a.getOverloads()[0].getOverloads()[0];
        argument.setName("item"); argument.setOptional(false);
        argument.getOptions().add(CommandParamOption.HAS_SEMANTIC_CONSTRAINT);
        argument.setType(null);
        argument.setEnumData(new CommandEnumData("shared", Map.of("stone", Set.of(CommandEnumConstraint.ALLOW_ALIASES)), false));
        b.getOverloads()[0].getOverloads()[0].setEnumData(PacketComposer.enumData("shared", List.of("dirt"), false));
        b.getOverloads()[0].getOverloads()[0].setType(null);
        original.getCommands().addAll(List.of(a, b));
        AtomicReference<AvailableCommandsPacket> baseline = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new CommandPacketInterceptor(Map::of, baseline::set, e -> fail(e)));
        try {
            channel.writeOutbound(BedrockPacketWrapper.create(0, 0, 0, original, null));
            BedrockPacketWrapper wrapper = channel.readOutbound();
            AvailableCommandsPacket written = (AvailableCommandsPacket) wrapper.getPacket();
            try {
                assertEquals(original, baseline.get());
                var first = written.getCommands().getFirst().getOverloads()[0].getOverloads()[0];
                var second = written.getCommands().getLast().getOverloads()[0].getOverloads()[0];
                assertNotEquals(first.getEnumData().getName(), second.getEnumData().getName());
                assertEquals(argument.getOptions(), first.getOptions());
                assertEquals(argument.isOptional(), first.isOptional());
                assertEquals(argument.getName(), first.getName());
                assertEquals(argument.getEnumData().getValues(), first.getEnumData().getValues());
                var codec = Bedrock_v2193.CODEC;
                var serializer = codec.getPacketDefinition(AvailableCommandsPacket.class).getSerializer();
                var buffer = Unpooled.buffer();
                try {
                    serializer.serialize(buffer, codec.createHelper(), written);
                    AvailableCommandsPacket decoded = new AvailableCommandsPacket();
                    serializer.deserialize(buffer, codec.createHelper(), decoded);
                    assertEquals(written, decoded);
                    assertEquals(0, buffer.readableBytes());
                } finally { buffer.release(); }
            } finally { wrapper.release(); }
        } finally { channel.finishAndReleaseAll(); }
    }
    @Test void handlesAliasAndParameterNameCollisionsRegardlessOfCommandOrder() {
        CommandEnumData aliases = PacketComposer.enumData("shared", List.of("native", "native_alias"), false);
        CommandData nativeCommand = new CommandData("native", "Description", Set.of(CommandData.Flag.NOT_CHEAT),
                CommandPermission.ANY, aliases, List.of(), new CommandOverloadData[0]);
        CommandData other = original("other", CommandParam.STRING);
        other.getOverloads()[0].getOverloads()[0].setEnumData(PacketComposer.enumData("shared", List.of("set"), false));
        AvailableCommandsPacket one = new AvailableCommandsPacket(); one.getCommands().addAll(List.of(nativeCommand, other));
        AvailableCommandsPacket two = new AvailableCommandsPacket(); two.getCommands().addAll(List.of(other, nativeCommand));
        var first = PacketComposer.uniqueHardEnums(one).getCommands();
        var second = PacketComposer.uniqueHardEnums(two).getCommands();
        assertEquals(first.getFirst(), second.getLast());
        assertEquals(first.getLast(), second.getFirst());
        assertEquals(aliases.getValues(), first.getFirst().getAliases().getValues());
        assertNotEquals(first.getFirst().getAliases().getName(), first.getLast().getOverloads()[0].getOverloads()[0].getEnumData().getName());
    }
    @Test void resolvesCollisionsWhilePreservingNonConflictingNativeEnums() {
        AvailableCommandsPacket packet = new AvailableCommandsPacket();
        CommandData literalA = original("example_a", CommandParam.STRING);
        CommandData literalB = original("example_b", CommandParam.STRING);
        literalA.getOverloads()[0].getOverloads()[0].setEnumData(PacketComposer.enumData("set", List.of("set"), false));
        literalB.getOverloads()[0].getOverloads()[0].setEnumData(PacketComposer.enumData("set", List.of("set", "add"), false));
        CommandData give = original("give", CommandParam.STRING);
        CommandData other = original("other", CommandParam.STRING);
        CommandEnumData items = PacketComposer.enumData("item_stack", List.of("minecraft:stone"), false);
        give.getOverloads()[0].getOverloads()[0].setEnumData(items);
        other.getOverloads()[0].getOverloads()[0].setEnumData(items);
        packet.getCommands().addAll(List.of(literalA, literalB, give, other));
        AvailableCommandsPacket normalized = PacketComposer.uniqueHardEnums(packet);
        assertSame(give, normalized.getCommands().get(2));
        assertSame(other, normalized.getCommands().get(3));
        assertSame(items, normalized.getCommands().get(2).getOverloads()[0].getOverloads()[0].getEnumData());
        var a = normalized.getCommands().get(0).getOverloads()[0].getOverloads()[0].getEnumData();
        var b = normalized.getCommands().get(1).getOverloads()[0].getOverloads()[0].getEnumData();
        assertNotEquals(a.getName(), b.getName());
        assertEquals(Set.of("set"), a.getValues().keySet());
        assertEquals(Set.of("set", "add"), b.getValues().keySet());
        assertEquals("set", literalA.getOverloads()[0].getOverloads()[0].getEnumData().getName());
        assertEquals(normalized, PacketComposer.uniqueHardEnums(normalized));
    }
    private static CommandData original(String name, CommandParam type) {
        CommandParamData args = new CommandParamData(); args.setName("args"); args.setOptional(true); args.setType(type);
        return new CommandData(name, "Description", Set.of(CommandData.Flag.NOT_CHEAT), CommandPermission.ANY,
                null, List.of(), new CommandOverloadData[]{new CommandOverloadData(false, new CommandParamData[]{args})});
    }
    private static AvailableCommandsPacket baseline() {
        AvailableCommandsPacket p = new AvailableCommandsPacket();
        p.getCommands().add(original("home", CommandParam.STRING));
        p.getCommands().add(original("native", CommandParam.INT));
        return p;
    }
    private static Command model(String value) {
        return new Command("home", List.of(new Node(List.of(), List.of("delete", "set")),
                new Node(List.of("delete"), List.of(value))));
    }
    @Test void preservesNativeDefinitionsAndArbitraryInputFallback() {
        AvailableCommandsPacket before = baseline();
        AvailableCommandsPacket after = PacketComposer.patch(before, Map.of("home", model("base")));
        assertSame(before.getCommands().get(1), after.getCommands().get(1));
        assertEquals(1, before.getCommands().getFirst().getOverloads().length);
        assertEquals(4, after.getCommands().getFirst().getOverloads().length);
        assertSame(before.getCommands().getFirst().getOverloads()[0], after.getCommands().getFirst().getOverloads()[0]);
        assertEquals(CommandParam.TEXT, after.getCommands().getFirst().getOverloads()[1].getOverloads()[0].getType());
        CommandParamData[] branch = after.getCommands().getFirst().getOverloads()[3].getOverloads();
        assertFalse(branch[0].getEnumData().isSoft());
        assertEquals(Set.of("delete"), branch[0].getEnumData().getValues().keySet());
        assertTrue(branch[1].getEnumData().isSoft());
    }
    @Test void detectsValueOnlyUpdatesSeparatelyFromStructureChanges() {
        assertTrue(PacketComposer.sameStructure(Map.of("home", model("a")), Map.of("home", model("b"))));
        assertFalse(PacketComposer.sameStructure(Map.of("home", model("a")), Map.of()));
        assertEquals(SoftEnumUpdateType.REPLACE, PacketComposer.replace("home", model("b").nodes().get(1)).getType());
        assertNotEquals(PacketComposer.enumName("a_b", List.of("c"), true), PacketComposer.enumName("a", List.of("b_c"), true));
    }
    @Test void realCloudburstCodecRoundTripsOverloadsAndSoftEnums() {
        var codec = Bedrock_v2193.CODEC;
        var helper = codec.createHelper();
        var serializer = codec.getPacketDefinition(AvailableCommandsPacket.class).getSerializer();
        var buffer = Unpooled.buffer();
        try {
            AvailableCommandsPacket original = PacketComposer.patch(baseline(), Map.of("home", model("private_base")));
            serializer.serialize(buffer, helper, original);
            AvailableCommandsPacket decoded = new AvailableCommandsPacket();
            serializer.deserialize(buffer, codec.createHelper(), decoded);
            assertEquals(original, decoded);
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }
    @Test void interceptsBeforeEncodingWithoutReplacingAuthoritativeBaselineWithGeneratedPackets() {
        AtomicReference<AvailableCommandsPacket> captured = new AtomicReference<>();
        CommandPacketInterceptor hook = new CommandPacketInterceptor(() -> Map.of("home", model("base")),
                captured::set, e -> fail(e));
        EmbeddedChannel channel = new EmbeddedChannel(hook);
        try {
            AvailableCommandsPacket original = baseline();
            var wrapper = BedrockPacketWrapper.create(0, 0, 0, original, null);
            assertTrue(channel.writeOutbound(wrapper));
            BedrockPacketWrapper written = channel.readOutbound();
            assertEquals(4, ((AvailableCommandsPacket) written.getPacket()).getCommands().getFirst().getOverloads().length);
            assertEquals(original, captured.get()); written.release();
            AvailableCommandsPacket generated = PacketComposer.patch(original, Map.of("home", model("updated")));
            hook.markGenerated(generated);
            channel.writeOutbound(BedrockPacketWrapper.create(0, 0, 0, generated, null));
            ((BedrockPacketWrapper) channel.readOutbound()).release();
            assertEquals(original, captured.get());
        } finally { channel.finishAndReleaseAll(); }
    }
    @Test void subclientsAndAlreadyEncodedPacketsPassThrough() {
        CommandPacketInterceptor hook = new CommandPacketInterceptor(() -> Map.of("home", model("base")),
                p -> fail("Must not capture subclient"), e -> fail(e));
        EmbeddedChannel channel = new EmbeddedChannel(hook);
        AvailableCommandsPacket p = baseline();
        try {
            channel.writeOutbound(BedrockPacketWrapper.create(0, 1, 0, p, null));
            BedrockPacketWrapper written = channel.readOutbound(); assertSame(p, written.getPacket()); written.release();
        } finally { channel.finishAndReleaseAll(); }
    }
}
