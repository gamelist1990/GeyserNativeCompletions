package com.pexserver.completions;

import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.command.*;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EffectBooleanHintsTest {
    private static CommandParamData typed(String name, CommandParam type) {
        var param = new CommandParamData(); param.setName(name); param.setType(type); return param;
    }
    private static CommandParamData choice(String name, String enumName, String... choices) {
        var param = new CommandParamData(); param.setName(name);
        param.setEnumData(PacketComposer.enumData(enumName, List.of(choices), false));
        return param;
    }
    private static CommandData effect(String name) {
        var give = choice("give", "give", "give");
        var target = typed("targets", CommandParam.TARGET);
        var effect = choice("effect", "mob_effect", "minecraft:speed", "minecraft:haste");
        var duration = typed("seconds", CommandParam.INT); duration.setOptional(true);
        var infinite = choice("infinite", "infinite", "infinite");
        var level = typed("amplifier", CommandParam.INT); level.setOptional(true);
        var bool = choice("hideParticles", "bool", "true", "false"); bool.setOptional(true);
        bool.getOptions().add(CommandParamOption.HAS_SEMANTIC_CONSTRAINT);
        var clear = new CommandOverloadData(false, new CommandParamData[]{choice("clear", "clear", "clear"), target, effect});
        var timed = new CommandOverloadData(false, new CommandParamData[]{give, target, effect, duration, level, bool});
        var secondBool = choice("hideParticles", "bool", "true", "false"); secondBool.setOptional(true);
        secondBool.getOptions().add(CommandParamOption.HAS_SEMANTIC_CONSTRAINT);
        var permanent = new CommandOverloadData(false, new CommandParamData[]{give, target, effect, infinite, level, secondBool});
        return new CommandData(name, "Effects", Set.of(CommandData.Flag.NOT_CHEAT), CommandPermission.GAME_DIRECTORS,
                null, List.of(), new CommandOverloadData[]{clear, timed, permanent});
    }
    @Test void effectBooleanIsSuggestedAtEndOfBothJavaOverloads() {
        for (String label : List.of("effect", "minecraft:effect")) {
            CommandData source = effect(label);
            AvailableCommandsPacket baseline = new AvailableCommandsPacket(); baseline.getCommands().add(source);
            CommandData patched = PacketComposer.patch(baseline, Map.of()).getCommands().getFirst();
            assertEquals(source.getOverloads().length, patched.getOverloads().length);
            assertEquals(source.getOverloads()[0].getOverloads()[0], patched.getOverloads()[0].getOverloads()[0]);
            assertTrue(patched.getOverloads()[0].getOverloads()[2].getEnumData().isSoft());
            for (int i = 1; i <= 2; i++) {
                var original = source.getOverloads()[i].getOverloads();
                var changed = patched.getOverloads()[i].getOverloads();
                for (int j = 0; j < changed.length - 1; j++) {
                    if (j == 2) {
                        assertEquals(original[j].getEnumData().getValues(), changed[j].getEnumData().getValues());
                        assertTrue(changed[j].getEnumData().isSoft());
                    } else assertEquals(original[j], changed[j]);
                }
                var last = changed[changed.length - 1];
                assertEquals("hideParticles", last.getName());
                assertTrue(last.isOptional());
                assertEquals(original[original.length - 1].getOptions(), last.getOptions());
                assertEquals(Set.of("true", "false"), last.getEnumData().getValues().keySet());
                assertTrue(last.getEnumData().isSoft());
                assertTrue(last.getEnumData().getName().startsWith("gnc_finite_"));
                assertEquals(patched.getOverloads()[1].getOverloads()[5].getEnumData().getName(), last.getEnumData().getName());
                assertFalse(original[original.length - 1].getEnumData().isSoft());
            }
            assertEquals(patched, JavaArgumentHints.knownChoices(patched));
            assertEquals(source, baseline.getCommands().getFirst());
            var codec = Bedrock_v2193.CODEC;
            var serializer = codec.getPacketDefinition(AvailableCommandsPacket.class).getSerializer();
            var buffer = Unpooled.buffer();
            try {
                serializer.serialize(buffer, codec.createHelper(), PacketComposer.patch(baseline, Map.of()));
                var decoded = new AvailableCommandsPacket(); serializer.deserialize(buffer, codec.createHelper(), decoded);
                var bool = decoded.getCommands().getFirst().getOverloads()[1].getOverloads()[5];
                assertTrue(bool.getEnumData().isSoft());
                assertEquals(Set.of("true", "false"), bool.getEnumData().getValues().keySet());
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
    }
    @Test void allCommandsWithFiniteEnumsAreHandledWithoutSpecialCases() {
        CommandData unrelated = effect("custom:effect_toggle");
        CommandData adapted = JavaArgumentHints.knownChoices(unrelated);
        assertNotSame(unrelated, adapted);
        assertTrue(adapted.getOverloads()[1].getOverloads()[5].getEnumData().isSoft());
        assertEquals(adapted, JavaArgumentHints.knownChoices(adapted));
        CommandData special = effect("effect");
        special.getOverloads()[1].getOverloads()[5].setEnumData(PacketComposer.enumData("custom", List.of("yes", "no"), false));
        CommandData patched = JavaArgumentHints.knownChoices(special);
        assertEquals(Set.of("yes", "no"), patched.getOverloads()[1].getOverloads()[5].getEnumData().getValues().keySet());
        assertTrue(patched.getOverloads()[1].getOverloads()[5].getEnumData().isSoft());
        assertTrue(patched.getOverloads()[2].getOverloads()[5].getEnumData().isSoft());
        assertNotEquals(patched.getOverloads()[1].getOverloads()[5].getEnumData().getName(),
                        patched.getOverloads()[2].getOverloads()[5].getEnumData().getName());
    }
}
