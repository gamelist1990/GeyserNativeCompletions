package com.pexserver.completions;

import org.cloudburstmc.protocol.bedrock.data.command.*;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSoftEnumPacket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static com.pexserver.completions.CompletionModel.*;

public final class PacketComposer {
    private static final Set<String> PARSER_NAMES = new HashSet<>();
    static {
        for (CommandParamType type : CommandParamType.values()) PARSER_NAMES.add(type.name().toLowerCase(Locale.ROOT));
    }
    private PacketComposer() {}
    public static boolean generic(CommandData data) {
        if (JavaArgumentHints.hasGameMode(data)) return false;
        CommandOverloadData[] overloads = data.getOverloads();
        if (overloads.length == 0) return true;
        if (overloads.length != 1) return false;
        CommandParamData[] args = overloads[0].getOverloads();
        return args.length == 0 || (args.length == 1 && args[0].getEnumData() == null &&
                (args[0].getType() == CommandParam.STRING || args[0].getType() == CommandParam.MESSAGE));
    }
    public static AvailableCommandsPacket copy(AvailableCommandsPacket packet) {
        AvailableCommandsPacket result = new AvailableCommandsPacket();
        result.getCommands().addAll(packet.getCommands());
        return result;
    }
    /**
     * A server-defined /help suppresses argument UI on Bedrock 26.52, even with zero overloads.
     * Leave that spelling to the client's built-in help; keep namespaced Java help and other aliases.
     * This must run on the initial command packet as well as subsequent completion updates.
     */
    public static AvailableCommandsPacket clientCompatible(AvailableCommandsPacket packet) {
        AvailableCommandsPacket result = new AvailableCommandsPacket();
        for (CommandData command : packet.getCommands()) {
            CommandEnumData aliases = command.getAliases();
            boolean helpName = command.getName().equals("help");
            boolean helpAlias = aliases != null && aliases.getValues().containsKey("help");
            if (!helpName && !helpAlias) {
                result.getCommands().add(command); continue;
            }
            Map<String, Set<CommandEnumConstraint>> values = aliases == null ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(aliases.getValues());
            values.remove("help");
            if (helpName && values.isEmpty()) continue;
            String name = helpName ? values.keySet().stream().sorted().findFirst().orElseThrow() : command.getName();
            CommandEnumData safeAliases = values.isEmpty() ? null
                    : new CommandEnumData(aliases.getName(), values, aliases.isSoft());
            result.getCommands().add(new CommandData(name, command.getDescription(), command.getFlags(), command.getPermission(),
                    safeAliases, command.getSubcommands(), command.getOverloads()));
        }
        return result;
    }
    /** Bedrock registers hard enums by name, while Cloudburst distinguishes name AND values. */
    public static AvailableCommandsPacket uniqueHardEnums(AvailableCommandsPacket packet) {
        Map<String, CommandEnumData> first = new HashMap<>();
        Set<String> conflicting = new HashSet<>();
        for (CommandData command : packet.getCommands()) {
            List<CommandEnumData> enums = new ArrayList<>();
            if (command.getAliases() != null) enums.add(command.getAliases());
            for (CommandOverloadData overload : command.getOverloads())
                for (CommandParamData parameter : overload.getOverloads())
                    if (parameter.getEnumData() != null && !parameter.getEnumData().isSoft()) enums.add(parameter.getEnumData());
            for (CommandEnumData data : enums) {
                if (PARSER_NAMES.contains(data.getName().toLowerCase(Locale.ROOT))) conflicting.add(data.getName());
                CommandEnumData previous = first.putIfAbsent(data.getName(), data);
                if (previous != null && !previous.getValues().equals(data.getValues())) conflicting.add(data.getName());
            }
        }
        if (conflicting.isEmpty()) return packet;
        AvailableCommandsPacket result = new AvailableCommandsPacket();
        for (CommandData command : packet.getCommands()) {
            CommandEnumData aliases = command.getAliases();
            if (aliases != null && conflicting.contains(aliases.getName())) aliases = uniqueEnum(aliases);
            boolean changed = aliases != command.getAliases();
            CommandOverloadData[] overloads = new CommandOverloadData[command.getOverloads().length];
            for (int i = 0; i < overloads.length; i++) {
                CommandOverloadData old = command.getOverloads()[i];
                CommandParamData[] parameters = old.getOverloads().clone();
                for (int j = 0; j < parameters.length; j++) {
                    CommandParamData p = parameters[j];
                    if (p.getEnumData() == null || p.getEnumData().isSoft() || !conflicting.contains(p.getEnumData().getName())) continue;
                    CommandParamData copy = new CommandParamData(); copy.setName(p.getName()); copy.setType(p.getType());
                    copy.setOptional(p.isOptional()); copy.setPostfix(p.getPostfix()); copy.getOptions().addAll(p.getOptions());
                    copy.setEnumData(uniqueEnum(p.getEnumData())); parameters[j] = copy; changed = true;
                }
                overloads[i] = new CommandOverloadData(old.isChaining(), parameters);
            }
            result.getCommands().add(changed ? new CommandData(command.getName(), command.getDescription(), command.getFlags(),
                    command.getPermission(), aliases, command.getSubcommands(), overloads) : command);
        }
        return result;
    }
    private static CommandEnumData uniqueEnum(CommandEnumData data) {
        List<String> content = data.getValues().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "\u0001" + entry.getValue().stream().map(Enum::name).sorted().toList()).toList();
        return new CommandEnumData(enumName(data.getName(), content, false), data.getValues(), false);
    }
    public static AvailableCommandsPacket patch(AvailableCommandsPacket baseline, Map<String, Command> models) {
        AvailableCommandsPacket result = new AvailableCommandsPacket();
        for (CommandData base : baseline.getCommands()) {
            CommandData original = JavaArgumentHints.gameModes(base);
            Command model = models.get(original.getName().toLowerCase(Locale.ROOT));
            if (model != null) original = arguments(original, model);
            if (model == null || model.nodes().isEmpty() || !generic(original)) {
                result.getCommands().add(original); continue;
            }
            List<CommandOverloadData> overloads = new ArrayList<>(Arrays.asList(original.getOverloads()));
            // Always accept arbitrary tails, even if the original tree carried no args.
            if (overloads.stream().noneMatch(o -> o.getOverloads().length == 1 &&
                    o.getOverloads()[0].getEnumData() == null && o.getOverloads()[0].getType() == CommandParam.TEXT)) {
                CommandParamData tail = new CommandParamData();
                // MESSAGE is an intermediate grammar symbol on current Bedrock clients;
                // TEXT is a displayable free-text root that accepts arbitrary command tails.
                tail.setName("args"); tail.setType(CommandParam.TEXT); tail.setOptional(true);
                overloads.add(new CommandOverloadData(false, new CommandParamData[]{tail}));
            }
            for (Node node : model.nodes()) {
                List<CommandParamData> params = new ArrayList<>();
                for (int i = 0; i < node.prefix().size(); i++) {
                    CommandParamData literal = new CommandParamData();
                    literal.setName("arg" + (i + 1));
                    literal.setEnumData(enumData(enumName(model.label(), node.prefix().subList(0, i + 1), false),
                            List.of(node.prefix().get(i)), false));
                    if (model.contexts().getOrDefault(node.prefix().subList(0, i), List.of()).contains(node.prefix().get(i)))
                        literal.getOptions().add(CommandParamOption.SUPPRESS_ENUM_AUTOCOMPLETION);
                    params.add(literal);
                }
                CommandParamData candidate = new CommandParamData();
                candidate.setName(node.prefix().isEmpty() ? "option" : "value");
                candidate.setOptional(true);
                candidate.setEnumData(enumData(enumName(model.label(), node.prefix(), true), node.candidates(), true));
                params.add(candidate);
                overloads.add(new CommandOverloadData(false, params.toArray(CommandParamData[]::new)));
            }
            result.getCommands().add(new CommandData(original.getName(), original.getDescription(), original.getFlags(),
                    original.getPermission(), original.getAliases(), original.getSubcommands(),
                    overloads.toArray(CommandOverloadData[]::new)));
        }
        return result;
    }
    private static CommandData arguments(CommandData command, Command model) {
        if (model.arguments().isEmpty()) return command;
        var overloads = command.getOverloads().clone();
        var enumNames = argumentEnumNames(model);
        record DisplayDomain(List<CommandParamData> prefix, String name, Set<String> candidates) {}
        Set<DisplayDomain> displayed = new HashSet<>();
        boolean changed = false;
        for (Argument argument : sortedArguments(model)) {
            ArgumentKey key = argument.key();
            if (key.overload() < 0 || key.overload() >= overloads.length) continue;
            var old = overloads[key.overload()];
            if (key.parameter() < 0 || key.parameter() >= old.getOverloads().length) continue;
            var p = old.getOverloads()[key.parameter()];
            if (!p.getName().equals(key.name()) || p.getType() != CommandParam.STRING || p.getEnumData() != null || argument.candidates().isEmpty()) continue;
            var parameters = old.getOverloads().clone();
            var copy = new CommandParamData();
            copy.setName(p.getName()); copy.setOptional(p.isOptional()); copy.setPostfix(p.getPostfix());
            copy.getOptions().addAll(p.getOptions());
            copy.setEnumData(enumData(enumNames.get(key), argument.candidates(), true));
            var prefix = List.copyOf(Arrays.asList(command.getOverloads()[key.overload()].getOverloads()).subList(0, key.parameter()));
            if (!displayed.add(new DisplayDomain(prefix, key.name(), Set.copyOf(argument.candidates()))))
                copy.getOptions().add(CommandParamOption.SUPPRESS_ENUM_AUTOCOMPLETION);
            parameters[key.parameter()] = copy;
            overloads[key.overload()] = new CommandOverloadData(old.isChaining(), parameters);
            changed = true;
        }
        return changed ? new CommandData(command.getName(), command.getDescription(), command.getFlags(), command.getPermission(),
                command.getAliases(), command.getSubcommands(), overloads) : command;
    }
    static String argumentEnumName(String label, ArgumentKey key) {
        return enumName(label, List.of("java", Integer.toString(key.overload()), Integer.toString(key.parameter()), key.name()), true);
    }
    private static List<Argument> sortedArguments(Command model) {
        return model.arguments().stream().sorted(Comparator.comparingInt((Argument a) -> a.key().overload())
                .thenComparingInt(a -> a.key().parameter()).thenComparing(a -> a.key().name())).toList();
    }
    /** Reuse one SoftEnum for equal domains; membership changes require a new command packet. */
    static Map<ArgumentKey, String> argumentEnumNames(Command model) {
        record Domain(int parameter, String name, Set<String> candidates) {}
        Map<Domain, String> shared = new HashMap<>();
        Map<ArgumentKey, String> names = new HashMap<>();
        for (Argument argument : sortedArguments(model)) {
            ArgumentKey key = argument.key();
            var domain = new Domain(key.parameter(), key.name(), Set.copyOf(argument.candidates()));
            names.put(key, shared.computeIfAbsent(domain, ignored -> argumentEnumName(model.label(), key)));
        }
        return Map.copyOf(names);
    }
    private static Set<List<String>> suppressedLiterals(Command model) {
        Set<List<String>> result = new HashSet<>();
        var contexts = model.contexts();
        for (Node node : model.nodes()) for (int i = 0; i < node.prefix().size(); i++)
            if (contexts.getOrDefault(node.prefix().subList(0, i), List.of()).contains(node.prefix().get(i)))
                result.add(List.copyOf(node.prefix().subList(0, i + 1)));
        return result;
    }
    public static String enumName(String label, List<String> prefix, boolean soft) {
        String readable = label.replaceAll("[^a-zA-Z0-9_]", "_");
        if (readable.length() > 24) readable = readable.substring(0, 24);
        String input = label + "\u0000" + String.join("\u0000", prefix) + (soft ? "S" : "H");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return readable + "_arg" + (prefix.size() + (soft ? 1 : 0)) + "_" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static CommandEnumData enumData(String name, List<String> values, boolean soft) {
        Map<String, Set<CommandEnumConstraint>> entries = new LinkedHashMap<>();
        values.forEach(v -> entries.put(v, Set.of()));
        return new CommandEnumData(name, entries, soft);
    }
    public static UpdateSoftEnumPacket replace(String label, Node node) {
        UpdateSoftEnumPacket result = new UpdateSoftEnumPacket();
        result.setType(SoftEnumUpdateType.REPLACE);
        result.setSoftEnum(enumData(enumName(label, node.prefix(), true), node.candidates(), true));
        return result;
    }
    public static UpdateSoftEnumPacket replace(String label, Argument argument) {
        return replaceArgument(argumentEnumName(label, argument.key()), argument);
    }
    public static UpdateSoftEnumPacket replace(Command model, Argument argument) {
        return replaceArgument(argumentEnumNames(model).get(argument.key()), argument);
    }
    private static UpdateSoftEnumPacket replaceArgument(String name, Argument argument) {
        var result = new UpdateSoftEnumPacket();
        result.setType(SoftEnumUpdateType.REPLACE);
        result.setSoftEnum(enumData(name, argument.candidates(), true));
        return result;
    }
    public static boolean sameStructure(Map<String, Command> old, Map<String, Command> next) {
        if (!old.keySet().equals(next.keySet())) return false;
        for (String key : old.keySet()) {
            if (!old.get(key).contexts().keySet().equals(next.get(key).contexts().keySet())) return false;
            if (!old.get(key).argumentContexts().keySet().equals(next.get(key).argumentContexts().keySet())) return false;
            if (!argumentEnumNames(old.get(key)).equals(argumentEnumNames(next.get(key)))) return false;
            if (!suppressedLiterals(old.get(key)).equals(suppressedLiterals(next.get(key)))) return false;
        }
        return true;
    }
}
