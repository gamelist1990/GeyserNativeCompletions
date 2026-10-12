package com.pexserver.completions;

import org.cloudburstmc.protocol.bedrock.data.command.*;
import java.util.*;

/** Restores finite Java argument choices that Bedrock may not show from Geyser's native mappings. */
final class JavaArgumentHints {
    private static final Set<String> MODE_COMMANDS = Set.of("gamemode", "defaultgamemode");
    private static final CommandEnumData GAME_MODES = PacketComposer.enumData("gnc_java_game_modes",
            List.of("survival", "creative", "adventure", "spectator"), true);
    private static final int MAX_ENUM_CHOICES = 64;
    private JavaArgumentHints() {}

    /** Use the actual Geyser/Java argument domain instead of special-casing particular commands. */
    static CommandData knownChoices(CommandData command) {
        return finiteArguments(gameModes(command));
    }

    private static CommandData finiteArguments(CommandData command) {
        CommandOverloadData[] overloads = command.getOverloads().clone();
        boolean changed = false;
        for (int i = 0; i < overloads.length; i++) {
            CommandOverloadData old = overloads[i];
            CommandParamData[] arguments = old.getOverloads().clone();
            boolean changedOverload = false;
            for (int j = 0; j < arguments.length; j++) {
                CommandParamData argument = arguments[j];
                if (!finiteDomain(argument)) continue;
                CommandEnumData source = argument.getEnumData();
                List<String> values = source.getValues().keySet().stream().sorted().toList();
                // Include the *domain* in the name: two enums with different values must never
                // share a Bedrock SoftEnum ID. The same domain reuses one ID across overloads.
                String name = PacketComposer.enumName("gnc_finite",
                        List.of(source.getName(), String.join("\u0001", values)), true);
                CommandParamData copy = new CommandParamData();
                copy.setName(argument.getName()); copy.setOptional(argument.isOptional());
                copy.setPostfix(argument.getPostfix()); copy.getOptions().addAll(argument.getOptions());
                copy.setEnumData(new CommandEnumData(name, source.getValues(), true));
                arguments[j] = copy;
                changedOverload = true;
            }
            if (changedOverload) {
                overloads[i] = new CommandOverloadData(old.isChaining(), arguments);
                changed = true;
            }
        }
        return changed ? new CommandData(command.getName(), command.getDescription(), command.getFlags(),
                command.getPermission(), command.getAliases(), command.getSubcommands(), overloads) : command;
    }

    private static boolean finiteDomain(CommandParamData argument) {
        CommandEnumData domain = argument.getEnumData();
        if (domain == null || domain.isSoft() || argument.getPostfix() != null ||
                argument.getOptions().contains(CommandParamOption.SUPPRESS_ENUM_AUTOCOMPLETION) ||
                domain.getValues().size() < 2 || domain.getValues().size() > MAX_ENUM_CHOICES) return false;
        // Soft enums cannot convey enum value constraints; keep these as hard enums.
        if (domain.getValues().values().stream().anyMatch(constraints -> !constraints.isEmpty())) return false;
        if (domain.getValues().keySet().stream().anyMatch(value -> !CompletionModel.validWord(value, 96))) return false;
        // Geyser names merged literal branches after the first literal. They form command
        // syntax (e.g. /effect give, /execute if/unless), not an argument's value domain.
        String name = domain.getName();
        return domain.getValues().keySet().stream()
                .noneMatch(value -> name.equals(value) || name.startsWith(value + "_arg"));
    }

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
