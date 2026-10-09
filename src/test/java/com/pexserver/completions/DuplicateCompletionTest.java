package com.pexserver.completions;

import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.command.*;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.pexserver.completions.CompletionModel.*;
import static org.junit.jupiter.api.Assertions.*;

class DuplicateCompletionTest {
    private static CommandParamData arg(String name, CommandParam type) {
        var p = new CommandParamData(); p.setName(name); p.setType(type); return p;
    }
    private static CommandParamData literal(String name) {
        var p = new CommandParamData(); p.setName(name); p.setEnumData(PacketComposer.enumData(name, List.of(name), false)); return p;
    }
    private static Command model(List<String> first, List<String> second) {
        return new Command("damage", List.of(), List.of(
                new Argument(new ArgumentKey(0, 2, "damageType"), first),
                new Argument(new ArgumentKey(1, 2, "damageType"), second)));
    }
    @Test void sharedDamageDomainHasOneCompletionSourceAndKeepsBothTypedBranchesAfterEncoding() {
        var command = new CommandData("damage", "Native damage", Set.of(), CommandPermission.ANY, null, List.of(),
                new CommandOverloadData[]{
                        new CommandOverloadData(false, new CommandParamData[]{arg("target", CommandParam.TARGET), arg("amount", CommandParam.FLOAT), arg("damageType", CommandParam.STRING), literal("at"), arg("location", CommandParam.POSITION)}),
                        new CommandOverloadData(false, new CommandParamData[]{arg("target", CommandParam.TARGET), arg("amount", CommandParam.FLOAT), arg("damageType", CommandParam.STRING), literal("by"), arg("entity", CommandParam.TARGET)})});
        var packet = new AvailableCommandsPacket(); packet.getCommands().add(command);
        var patched = PacketComposer.patch(packet, Map.of("damage", model(List.of("minecraft:fall", "minecraft:arrow"), List.of("minecraft:arrow", "minecraft:fall"))));
        var codec = Bedrock_v2193.CODEC;
        var serializer = codec.getPacketDefinition(AvailableCommandsPacket.class).getSerializer();
        var buffer = Unpooled.buffer();
        try {
            serializer.serialize(buffer, codec.createHelper(), patched);
            var decoded = new AvailableCommandsPacket(); serializer.deserialize(buffer, codec.createHelper(), decoded);
            var first = decoded.getCommands().getFirst().getOverloads()[0].getOverloads();
            var second = decoded.getCommands().getFirst().getOverloads()[1].getOverloads();
            assertEquals(first[2].getEnumData().getName(), second[2].getEnumData().getName());
            assertEquals(Set.of("minecraft:arrow", "minecraft:fall"), first[2].getEnumData().getValues().keySet());
            assertFalse(first[2].getOptions().contains(CommandParamOption.SUPPRESS_ENUM_AUTOCOMPLETION));
            assertTrue(second[2].getOptions().contains(CommandParamOption.SUPPRESS_ENUM_AUTOCOMPLETION));
            assertEquals(command.getOverloads()[0].getOverloads()[3], first[3]);
            assertEquals(command.getOverloads()[1].getOverloads()[3], second[3]);
            assertEquals(CommandParam.TARGET, first[0].getType()); assertEquals(CommandParam.FLOAT, second[1].getType());
            assertTrue(command.getOverloads()[1].getOverloads()[2].getOptions().isEmpty());
        } finally { buffer.release(); }
    }
    @Test void differentPriorLiteralBranchesKeepTheirOwnCompletionVisibility() {
        var command = new CommandData("example", "", Set.of(), CommandPermission.ANY, null, List.of(),
                new CommandOverloadData[]{
                        new CommandOverloadData(false, new CommandParamData[]{literal("add"), arg("choice", CommandParam.STRING)}),
                        new CommandOverloadData(false, new CommandParamData[]{literal("remove"), arg("choice", CommandParam.STRING)})});
        var model = new Command("example", List.of(), List.of(
                new Argument(new ArgumentKey(0, 1, "choice"), List.of("value")),
                new Argument(new ArgumentKey(1, 1, "choice"), List.of("value"))));
        var packet = new AvailableCommandsPacket(); packet.getCommands().add(command);
        var patched = PacketComposer.patch(packet, Map.of("example", model)).getCommands().getFirst();
        for (var overload : patched.getOverloads())
            assertFalse(overload.getOverloads()[1].getOptions().contains(CommandParamOption.SUPPRESS_ENUM_AUTOCOMPLETION));
    }
    @Test void sharedUpdatesUseOneEnumAndDomainSplitOrMergeRebuildsTheDefinition() {
        var old = model(List.of("a"), List.of("a"));
        var changed = model(List.of("b"), List.of("b"));
        assertTrue(PacketComposer.sameStructure(Map.of("damage", old), Map.of("damage", changed)));
        assertEquals(PacketComposer.replace(changed, changed.arguments().get(0)), PacketComposer.replace(changed, changed.arguments().get(1)));
        var split = model(List.of("b"), List.of("c"));
        assertFalse(PacketComposer.sameStructure(Map.of("damage", changed), Map.of("damage", split)));
        assertFalse(PacketComposer.sameStructure(Map.of("damage", split), Map.of("damage", changed)));
    }
    @Test void genericLiteralAlreadySuggestedByParentIsSuppressedButStillAcceptsItsTail() {
        var base = new CommandData("home", "", Set.of(), CommandPermission.ANY, null, List.of(), new CommandOverloadData[0]);
        var shared = new Command("home", List.of(new Node(List.of(), List.of("delete", "set")), new Node(List.of("delete"), List.of("base"))));
        var extraOnly = new Command("home", List.of(new Node(List.of(), List.of("set")), new Node(List.of("delete"), List.of("base"))));
        var packet = new AvailableCommandsPacket(); packet.getCommands().add(base);
        var patched = PacketComposer.patch(packet, Map.of("home", shared)).getCommands().getFirst();
        var branch = Arrays.stream(patched.getOverloads()).filter(o -> o.getOverloads().length == 2).findFirst().orElseThrow().getOverloads();
        assertTrue(branch[0].getOptions().contains(CommandParamOption.SUPPRESS_ENUM_AUTOCOMPLETION));
        assertEquals(Set.of("delete"), branch[0].getEnumData().getValues().keySet());
        assertEquals(Set.of("base"), branch[1].getEnumData().getValues().keySet());
        var extra = PacketComposer.patch(packet, Map.of("home", extraOnly)).getCommands().getFirst();
        var extraBranch = Arrays.stream(extra.getOverloads()).filter(o -> o.getOverloads().length == 2).findFirst().orElseThrow().getOverloads();
        assertFalse(extraBranch[0].getOptions().contains(CommandParamOption.SUPPRESS_ENUM_AUTOCOMPLETION));
        assertFalse(PacketComposer.sameStructure(Map.of("home", shared), Map.of("home", extraOnly)));
    }
}
