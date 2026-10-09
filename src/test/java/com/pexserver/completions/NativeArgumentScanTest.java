package com.pexserver.completions;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.command.*;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static com.pexserver.completions.CompletionModel.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeArgumentScanTest {
    private final Limits limits = new Limits(3, 32, 64, 8, 96);
    private static CommandParamData arg(String name, CommandParam type) {
        var p = new CommandParamData(); p.setName(name); p.setType(type); return p;
    }
    private static CommandData command() {
        var choice = arg("choice", CommandParam.STRING); choice.setOptional(true);
        return new CommandData("native", "typed", Set.of(), CommandPermission.ANY, null, List.of(),
                new CommandOverloadData[]{new CommandOverloadData(false,
                        new CommandParamData[]{arg("amount", CommandParam.INT), choice})});
    }
    private static CompletionModel.Command finish(NativeArgumentScan scan) {
        for (int i = 0; !scan.done() && i < 1000; i++) scan.step();
        assertTrue(scan.done()); return scan.result();
    }
    @Test void actualBrigadierSuggestionsBecomeTypedArgumentSoftEnumsAndRefresh() {
        var dispatcher = new CommandDispatcher<List<String>>();
        dispatcher.register(LiteralArgumentBuilder.<List<String>>literal("native")
                .then(RequiredArgumentBuilder.<List<String>, Integer>argument("amount", IntegerArgumentType.integer())
                        .then(RequiredArgumentBuilder.<List<String>, String>argument("choice", StringArgumentType.word())
                                .suggests((context, builder) -> { context.getSource().forEach(builder::suggest); return builder.buildFuture(); }))));
        List<String> playerValues = new ArrayList<>(List.of("minecraft:in_fire", "minecraft:fall"));
        var provider = new JavaSuggestions<>(dispatcher, playerValues);
        var model = finish(new NativeArgumentScan(command(), limits, provider));
        assertEquals(Set.copyOf(playerValues), Set.copyOf(model.arguments().getFirst().candidates()));
        AvailableCommandsPacket input = new AvailableCommandsPacket(); input.getCommands().add(command());
        var patched = PacketComposer.patch(input, Map.of("native", model));
        var codec = Bedrock_v2193.CODEC;
        var serializer = codec.getPacketDefinition(AvailableCommandsPacket.class).getSerializer();
        var buffer = Unpooled.buffer();
        try {
            serializer.serialize(buffer, codec.createHelper(), patched);
            var decoded = new AvailableCommandsPacket(); serializer.deserialize(buffer, codec.createHelper(), decoded);
            var output = decoded.getCommands().getFirst().getOverloads()[0].getOverloads();
            assertEquals(CommandParam.INT, output[0].getType());
            assertTrue(output[1].isOptional()); assertTrue(output[1].getEnumData().isSoft());
            assertEquals(Set.copyOf(playerValues), output[1].getEnumData().getValues().keySet());
            assertNull(input.getCommands().getFirst().getOverloads()[0].getOverloads()[1].getEnumData());
        } finally { buffer.release(); }
        playerValues.add("new_value");
        var changed = finish(new NativeArgumentScan(command(), limits, provider));
        assertTrue(PacketComposer.sameStructure(Map.of("native", model), Map.of("native", changed)));
        var replace = PacketComposer.replace("native", changed.arguments().getFirst());
        assertEquals(SoftEnumUpdateType.REPLACE, replace.getType());
        assertTrue(replace.getSoftEnum().getValues().containsKey("new_value"));
        assertEquals(patched.getCommands().getFirst().getOverloads()[0].getOverloads()[1].getEnumData().getName(), replace.getSoftEnum().getName());
    }
    @Test void invalidEarlierArgumentAndDeniedRootCannotSupplyChoices() {
        var dispatcher = new CommandDispatcher<Boolean>();
        dispatcher.register(LiteralArgumentBuilder.<Boolean>literal("native").requires(Boolean::booleanValue)
                .then(RequiredArgumentBuilder.<Boolean, Integer>argument("amount", IntegerArgumentType.integer())
                        .then(RequiredArgumentBuilder.<Boolean, String>argument("choice", StringArgumentType.word())
                                .suggests((context, builder) -> builder.suggest("secret").buildFuture()))));
        assertTrue(new JavaSuggestions<>(dispatcher, false).complete("native 1 ", "choice").join().isEmpty());
        assertTrue(new JavaSuggestions<>(dispatcher, true).complete("native wrong ", "choice").join().isEmpty());
        assertTrue(new JavaSuggestions<>(dispatcher, true).complete("native ", "choice").join().isEmpty());
        assertEquals(List.of("secret"), new JavaSuggestions<>(dispatcher, true).complete("native 1 ", "choice").join());
    }
    @Test void pendingSuggestionsDoNotBlockAndUnsafeOrExcessValuesAreFiltered() {
        var future = new CompletableFuture<List<String>>();
        var scan = new NativeArgumentScan(command(), new Limits(3, 32, 2, 8, 12), (input, name) -> future);
        scan.step(); scan.step(); // amount sample, asynchronous choice query
        assertFalse(scan.done()); scan.step(); assertFalse(scan.done());
        future.complete(Arrays.asList("\"quoted\"", "two words", null, "first", "second", "third"));
        var result = finish(scan);
        assertEquals(List.of("first", "second"), result.arguments().getFirst().candidates());
    }
    @Test void literalBranchesUseValidTargetAndFloatSamplesAndStaleKeysAreIgnored() {
        var literal = new CommandParamData(); literal.setName("verb");
        literal.setEnumData(PacketComposer.enumData("verb", List.of("add", "remove"), false));
        var data = new CommandData("native", "", Set.of(), CommandPermission.ANY, null, List.of(),
                new CommandOverloadData[]{new CommandOverloadData(false, new CommandParamData[]{
                        arg("target", CommandParam.TARGET), arg("amount", CommandParam.FLOAT), literal, arg("choice", CommandParam.STRING)})});
        List<String> inputs = new ArrayList<>();
        var result = finish(new NativeArgumentScan(data, limits, (input, name) -> {
            inputs.add(input); return CompletableFuture.completedFuture(List.of(input.contains("remove") ? "old" : "new"));
        }));
        assertEquals(List.of("native @s 1 add ", "native @s 1 remove "), inputs);
        assertEquals(List.of("new", "old"), result.arguments().getFirst().candidates());
        var stale = new CompletionModel.Command("native", List.of(), List.of(new Argument(new ArgumentKey(0, 3, "different"), List.of("bad"))));
        var packet = new AvailableCommandsPacket(); packet.getCommands().add(data);
        assertSame(data, PacketComposer.patch(packet, Map.of("native", stale)).getCommands().getFirst());
    }
    @Test void duplicateOverloadsShareOneQueryAndUnneededTailsAreNotExplored() {
        var base = command();
        var tail = new CommandParamData(); tail.setName("branch");
        tail.setEnumData(PacketComposer.enumData("branch", List.of("one", "two", "three", "four"), false));
        var args = new CommandParamData[]{base.getOverloads()[0].getOverloads()[0], base.getOverloads()[0].getOverloads()[1], tail};
        var overload = new CommandOverloadData(false, args);
        var data = new CommandData("native", "", Set.of(), CommandPermission.ANY, null, List.of(),
                new CommandOverloadData[]{overload, overload});
        List<String> calls = new ArrayList<>();
        var model = finish(new NativeArgumentScan(data, limits, (input, name) -> {
            calls.add(input); return CompletableFuture.completedFuture(List.of("value"));
        }));
        assertEquals(List.of("native 1 "), calls);
        assertEquals(2, model.arguments().size());
    }
}
