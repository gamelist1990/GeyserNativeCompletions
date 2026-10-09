package com.pexserver.completions;

import org.cloudburstmc.protocol.bedrock.data.command.*;
import java.util.*;

/** Finite Java argument domains lost by Geyser's parser-to-Bedrock type mapping. */
final class JavaArgumentHints {
    private static final Set<String> MODE_COMMANDS = Set.of("gamemode", "defaultgamemode");
    private static final CommandEnumData GAME_MODES = PacketComposer.enumData("gnc_java_game_modes",
            List.of("survival", "creative", "adventure", "spectator"), true);
    private JavaArgumentHints() {}

    static boolean hasGameMode(CommandData command) {
        if (!modeCommand(command)) return false;
        for (CommandOverloadData overload : command.getOverloads())
            for (CommandParamData argument : overload.getOverloads()) if (modeArgument(argument)) return true;
        return false;
    }
    static CommandData gameModes(CommandData command) {
        if (!hasGameMode(command)) return command;
        CommandOverloadData[] overloads = command.getOverloads().clone();
        for (int i = 0; i < overloads.length; i++) {
            CommandOverloadData old = overloads[i];
            CommandParamData[] arguments = old.getOverloads().clone();
            for (int j = 0; j < arguments.length; j++) {
                CommandParamData argument = arguments[j];
                if (!modeArgument(argument)) continue;
                CommandParamData copy = new CommandParamData();
                copy.setName(argument.getName()); copy.setOptional(argument.isOptional());
                copy.setPostfix(argument.getPostfix()); copy.getOptions().addAll(argument.getOptions());
                copy.setEnumData(GAME_MODES); arguments[j] = copy;
            }
            overloads[i] = new CommandOverloadData(old.isChaining(), arguments);
        }
        return new CommandData(command.getName(), command.getDescription(), command.getFlags(), command.getPermission(),
                command.getAliases(), command.getSubcommands(), overloads);
    }
    private static boolean modeArgument(CommandParamData argument) {
        return argument.getEnumData() == null && argument.getType() == CommandParam.STRING &&
                argument.getName().equals("gamemode");
    }
    private static boolean modeCommand(CommandData command) {
        if (MODE_COMMANDS.contains(baseName(command.getName()))) return true;
        return command.getAliases() != null && command.getAliases().getValues().keySet().stream()
                .anyMatch(name -> MODE_COMMANDS.contains(baseName(name)));
    }
    private static String baseName(String name) { return name.substring(name.lastIndexOf(':') + 1); }
}
