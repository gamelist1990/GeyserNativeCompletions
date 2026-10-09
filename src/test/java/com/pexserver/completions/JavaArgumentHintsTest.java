package com.pexserver.completions;

import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.command.*;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.pexserver.completions.CompletionModel.*;

class JavaArgumentHintsTest {
    private static CommandData command(String name, CommandEnumData aliases) {
        CommandParamData mode = new CommandParamData(); mode.setName("gamemode"); mode.setType(CommandParam.STRING);
        CommandParamData target = new CommandParamData(); target.setName("target"); target.setType(CommandParam.TARGET); target.setOptional(true);
        return new CommandData(name, "Native modes", Set.of(CommandData.Flag.NOT_CHEAT), CommandPermission.GAME_DIRECTORS,
                aliases, List.of(), new CommandOverloadData[]{new CommandOverloadData(false, new CommandParamData[]{mode, target})});
    }
    @Test void encodedGameModesHaveJavaChoicesAndPreserveTargetsAndCommandMetadata() {
        for (String name : List.of("gamemode", "minecraft:gamemode", "defaultgamemode")) {
            CommandData original = command(name, PacketComposer.enumData(name + "Aliases", List.of(name), false));
            AvailableCommandsPacket input = new AvailableCommandsPacket(); input.getCommands().add(original);
            var codec = Bedrock_v2193.CODEC;
            var serializer = codec.getPacketDefinition(AvailableCommandsPacket.class).getSerializer();
            var buffer = Unpooled.buffer();
            try {
                serializer.serialize(buffer, codec.createHelper(), PacketComposer.patch(input, Map.of()));
                var decoded = new AvailableCommandsPacket(); serializer.deserialize(buffer, codec.createHelper(), decoded);
                CommandData output = decoded.getCommands().getFirst();
                var parameters = output.getOverloads()[0].getOverloads();
                assertEquals(Set.of("survival", "creative", "adventure", "spectator"), parameters[0].getEnumData().getValues().keySet());
                assertTrue(parameters[0].getEnumData().isSoft());
                assertFalse(parameters[0].isOptional());
                assertEquals(original.getOverloads()[0].getOverloads()[1], parameters[1]);
                assertEquals(original.getAliases(), output.getAliases());
                assertEquals(original.getFlags(), output.getFlags());
                assertEquals(original.getPermission(), output.getPermission());
                assertEquals(original.getDescription(), output.getDescription());
                assertEquals(CommandParam.STRING, original.getOverloads()[0].getOverloads()[0].getType());
                assertNull(original.getOverloads()[0].getOverloads()[0].getEnumData());
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
    }
    @Test void aliasRecognitionDoesNotOverwriteExistingEnumsOrUnrelatedStringArguments() {
        CommandData aliased = command("modealias", PacketComposer.enumData("aliases", List.of("modealias", "gamemode"), false));
        assertTrue(JavaArgumentHints.hasGameMode(aliased));
        assertFalse(PacketComposer.generic(aliased));
        CommandData unrelated = command("custom", null);
        assertSame(unrelated, JavaArgumentHints.gameModes(unrelated));
        CommandData existing = command("gamemode", null);
        existing.getOverloads()[0].getOverloads()[0].setEnumData(PacketComposer.enumData("customModes", List.of("custom"), false));
        assertSame(existing, JavaArgumentHints.gameModes(existing));
    }
    @Test void defaultGameModeUsesItsFiniteArgumentInsteadOfGenericPlayerNameCompletion() {
        CommandData nativeCommand = command("defaultgamemode", null);
        nativeCommand.getOverloads()[0] = new CommandOverloadData(false,
                new CommandParamData[]{nativeCommand.getOverloads()[0].getOverloads()[0]});
        assertFalse(PacketComposer.generic(nativeCommand));
        AvailableCommandsPacket input = new AvailableCommandsPacket(); input.getCommands().add(nativeCommand);
        var model = new Command("defaultgamemode", List.of(new Node(List.of(), List.of("player_name"))));
        CommandData output = PacketComposer.patch(input, Map.of("defaultgamemode", model)).getCommands().getFirst();
        assertEquals(1, output.getOverloads().length);
        assertFalse(output.getOverloads()[0].getOverloads()[0].getEnumData().getValues().containsKey("player_name"));
        assertEquals(output, JavaArgumentHints.gameModes(output));
    }
}
