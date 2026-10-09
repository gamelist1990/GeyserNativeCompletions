package com.pexserver.completions.fixture;

import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.java.ServerDefineCommandsEvent;
import org.geysermc.geyser.session.GeyserSession;
import org.cloudburstmc.protocol.bedrock.data.command.*;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import java.util.*;

/** Test-only jar, never bundled into the production plugin. */
public final class FixturePlugin extends JavaPlugin {
    private final Map<UUID, List<String>> homes = new HashMap<>();
    private final Map<UUID, AvailableCommandsPacket> diagnosticBaselines = new HashMap<>();
    private final Set<UUID> minimal = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final EventRegistrar registrar = new EventRegistrar() {};
    @Override public void onEnable() {
        getLifecycleManager().registerEventHandler(io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS, event -> {
            var root = io.papermc.paper.command.brigadier.Commands.literal("ncf_native")
                    .then(io.papermc.paper.command.brigadier.Commands.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                            .then(io.papermc.paper.command.brigadier.Commands.argument("choice", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .suggests((context, builder) -> {
                                        if (context.getSource().getSender() instanceof org.bukkit.entity.Player p)
                                            homes.computeIfAbsent(p.getUniqueId(), id -> new ArrayList<>(List.of(p.getName() + "_base")))
                                                    .forEach(builder::suggest);
                                        return builder.buildFuture();
                                    }).executes(context -> 1)));
            event.registrar().register(root.build(), "Native dynamic fixture");
        });
        String diagnosticPlayer = System.getProperty("ncf.diag.player", "");
        if (!diagnosticPlayer.isEmpty()) minimal.add(UUID.fromString(diagnosticPlayer));
        String helpVariant = System.getProperty("ncf.diag.help", "");
        if (!helpVariant.isEmpty()) getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler public void joined(org.bukkit.event.player.PlayerJoinEvent event) {
                if (!event.getPlayer().getName().equalsIgnoreCase("PEXKoukun")) return;
                getServer().getScheduler().runTaskLater(FixturePlugin.this, () -> {
                    if (event.getPlayer().isOnline()) Objects.requireNonNull(getCommand("ncfdiag")).execute(
                            getServer().getConsoleSender(), "ncfdiag", new String[]{"helpfix", event.getPlayer().getName(), helpVariant});
                }, 60L);
            }
        }, this);
        // Geyser initializes its event bus after Bukkit plugin enable callbacks.
        getServer().getScheduler().runTaskLater(this, () -> GeyserApi.api().eventBus().subscribe(registrar, ServerDefineCommandsEvent.class, event -> {
            if (minimal.contains(event.connection().javaUuid())) {
                event.commands().removeIf(info -> !Set.of("ncf", "gamemode", "help").contains(
                        info.name().substring(info.name().indexOf(':') + 1)));
            }
        }), 100L);
        Objects.requireNonNull(getCommand("ncfdiag")).setExecutor((sender, command, alias, args) -> {
            if (args.length == 0 || !Set.of("minimal", "full", "member", "operator", "enum", "soft", "string", "message", "raw", "combined", "matrix", "inspect", "subset", "helpfix", "selector").contains(args[0])) return false;
            org.bukkit.entity.Player target = args.length > 1 ? getServer().getPlayerExact(args[1])
                    : sender instanceof org.bukkit.entity.Player p ? p : null;
            if (target == null) { sender.sendMessage("Player is not online"); return true; }
            if (args[0].equals("selector")) {
                try {
                    AvailableCommandsPacket baseline = nativeBaseline(target.getUniqueId());
                    AvailableCommandsPacket packet = new AvailableCommandsPacket();
                    LinkedHashSet<String> candidates = new LinkedHashSet<>(List.of("@a", "@e", "@n", "@p", "@r", "@s"));
                    for (String selector : List.copyOf(candidates)) {
                        for (String option : List.of("name", "distance", "level", "x", "y", "z", "dx", "dy", "dz", "x_rotation",
                                "y_rotation", "limit", "sort", "gamemode", "team", "type", "tag", "nbt", "scores", "advancements", "predicate"))
                            candidates.add(selector + "[" + option + "=");
                        for (String mode : List.of("survival", "creative", "adventure", "spectator")) candidates.add(selector + "[gamemode=" + mode + "]");
                        for (String sort : List.of("nearest", "furthest", "random", "arbitrary")) candidates.add(selector + "[sort=" + sort + "]");
                        for (String entity : List.of("minecraft:player", "minecraft:pig", "minecraft:zombie")) candidates.add(selector + "[type=" + entity + "]");
                        candidates.add(selector + "[distance=..10]");
                    }
                    getServer().getOnlinePlayers().forEach(player -> candidates.add(player.getName()));
                    Map<String, Set<CommandEnumConstraint>> entries = new LinkedHashMap<>();
                    candidates.forEach(value -> entries.put(value, Set.of()));
                    CommandEnumData choices = new CommandEnumData("ncf_java_selectors_probe", entries, true);
                    for (CommandData data : baseline.getCommands()) {
                        boolean tag = data.getName().equals("tag") || data.getAliases() != null && data.getAliases().getValues().containsKey("tag");
                        if (!tag) { packet.getCommands().add(data); continue; }
                        CommandOverloadData[] overloads = data.getOverloads().clone();
                        for (int i = 0; i < overloads.length; i++) {
                            CommandOverloadData old = overloads[i]; CommandParamData[] parameters = old.getOverloads().clone();
                            for (int j = 0; j < parameters.length; j++) {
                                var original = parameters[j];
                                if (original.getType() != CommandParam.TARGET) continue;
                                var copy = new CommandParamData(); copy.setName(original.getName()); copy.setOptional(original.isOptional());
                                copy.setPostfix(original.getPostfix()); copy.getOptions().addAll(original.getOptions());
                                copy.setEnumData(choices); parameters[j] = copy;
                            }
                            overloads[i] = new CommandOverloadData(old.isChaining(), parameters);
                        }
                        packet.getCommands().add(new CommandData(data.getName(), data.getDescription(), data.getFlags(), data.getPermission(),
                                data.getAliases(), data.getSubcommands(), overloads));
                    }
                    if (GeyserApi.api().connectionByUuid(target.getUniqueId()) instanceof GeyserSession session)
                        session.getUpstream().getSession().getPeer().getChannel().eventLoop().execute(() -> session.sendUpstreamPacket(packet));
                    sender.sendMessage("Java selector SoftEnum probe sent for /tag: " + candidates.size() + " values");
                } catch (ReflectiveOperationException | RuntimeException e) {
                    getLogger().log(java.util.logging.Level.WARNING, "Unable to send selector diagnostic", e);
                }
                return true;
            }
            if (args[0].equals("helpfix")) {
                try {
                    AvailableCommandsPacket baseline = diagnosticBaselines.computeIfAbsent(target.getUniqueId(), id -> {
                        try { return nativeBaseline(id); }
                        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                    });
                    String variant = args.length > 2 ? args[2] : "text";
                    boolean isolated = args.length > 3 && args[3].equals("isolated");
                    Set<String> controls = Set.of("give", "gamemode", "effect", "gamerule", "ncf");
                    AvailableCommandsPacket packet = new AvailableCommandsPacket();
                    for (CommandData data : baseline.getCommands()) {
                        CommandData source = data;
                        String control = controls.stream().filter(name -> source.getName().equals(name) ||
                                source.getAliases() != null && source.getAliases().getValues().containsKey(name)).findFirst().orElse(null);
                        if (isolated && control == null && !data.getName().equals("help")) continue;
                        if (data.getName().equals("help")) {
                            if (variant.equals("omit")) continue;
                            if (variant.equals("rename")) data = named(data, "ncf_help_copy");
                            else if (!variant.equals("original")) {
                                CommandParamData parameter = new CommandParamData();
                                parameter.setName("args"); parameter.setOptional(true);
                                parameter.setType(variant.equals("command") ? CommandParam.COMMAND : CommandParam.TEXT);
                                CommandOverloadData[] overloads = variant.equals("empty") ? new CommandOverloadData[0]
                                        : new CommandOverloadData[]{new CommandOverloadData(false, new CommandParamData[]{parameter})};
                                data = new CommandData(data.getName(), data.getDescription(), data.getFlags(), data.getPermission(),
                                        data.getAliases(), data.getSubcommands(), overloads);
                            }
                        }
                        packet.getCommands().add(isolated ? named(data, control == null ? data.getName() : control) : data);
                    }
                    if (GeyserApi.api().connectionByUuid(target.getUniqueId()) instanceof GeyserSession session)
                        session.getUpstream().getSession().getPeer().getChannel().eventLoop().execute(() -> session.sendUpstreamPacket(packet));
                    sender.sendMessage("Help diagnostic " + variant + ", isolated=" + isolated + ", commands=" + packet.getCommands().size());
                } catch (RuntimeException e) {
                    getLogger().log(java.util.logging.Level.WARNING, "Unable to send help diagnostic", e);
                }
                return true;
            }
            if (args[0].equals("subset")) {
                try {
                    AvailableCommandsPacket baseline = diagnosticBaselines.get(target.getUniqueId());
                    if (baseline == null) {
                        baseline = nativeBaseline(target.getUniqueId());
                        diagnosticBaselines.put(target.getUniqueId(), baseline);
                    }
                    Set<String> controls = Set.of("give", "gamemode", "effect", "gamerule", "ncf");
                    List<CommandData> other = new ArrayList<>();
                    AvailableCommandsPacket packet = new AvailableCommandsPacket();
                    for (CommandData data : baseline.getCommands()) {
                        String label = controls.stream().filter(name -> data.getName().equals(name) ||
                                data.getAliases() != null && data.getAliases().getValues().containsKey(name)).findFirst().orElse(null);
                        if (label == null) other.add(data);
                        else packet.getCommands().add(named(data, label));
                    }
                    other.sort(Comparator.comparing(CommandData::getName));
                    int from = args.length > 2 ? Integer.parseInt(args[2]) : 0;
                    int to = args.length > 3 ? Integer.parseInt(args[3]) : 0;
                    for (CommandData data : other.subList(from, to)) packet.getCommands().add(named(data, data.getName()));
                    if (GeyserApi.api().connectionByUuid(target.getUniqueId()) instanceof GeyserSession session)
                        session.getUpstream().getSession().getPeer().getChannel().eventLoop().execute(() -> session.sendUpstreamPacket(packet));
                    sender.sendMessage("Subset " + from + ".." + to + " of " + other.size() + ": " + other.subList(from, to).stream().map(CommandData::getName).toList());
                } catch (ReflectiveOperationException | RuntimeException e) {
                    getLogger().log(java.util.logging.Level.WARNING, "Unable to send subset", e);
                    sender.sendMessage("No diagnostic baseline available");
                }
                return true;
            }
            if (args[0].equals("inspect")) {
                sender.sendMessage("Java chat visibility: " + target.getClientOption(com.destroystokyo.paper.ClientOption.CHAT_VISIBILITY));
                sender.sendMessage("Player dead=" + target.isDead() + " health=" + target.getHealth() + " valid=" + target.isValid());
                if (GeyserApi.api().connectionByUuid(target.getUniqueId()) instanceof GeyserSession session)
                    sender.sendMessage("Geyser loggedIn=" + session.isLoggedIn() + " initialized=" + session.getUpstream().isInitialized() + " entity=" + session.getPlayerEntity().geyserId());
                return true;
            }
            if (args[0].equals("matrix")) {
                try {
                    AvailableCommandsPacket baseline = nativeBaseline(target.getUniqueId());
                    CommandData give = baseline.getCommands().stream().filter(data -> data.getName().equals("give") ||
                            data.getAliases() != null && data.getAliases().getValues().containsKey("give")).findFirst().orElseThrow();
                    AvailableCommandsPacket diagnostic = new AvailableCommandsPacket();
                    diagnostic.getCommands().add(giveProbe(give, "give", 0));
                    diagnostic.getCommands().add(giveProbe(give, "ncf_give_copy", 0));
                    diagnostic.getCommands().add(giveProbe(give, "ncf_give_fresh", 1));
                    diagnostic.getCommands().add(giveProbe(give, "ncf_give_soft", 2));
                    if (GeyserApi.api().connectionByUuid(target.getUniqueId()) instanceof GeyserSession session)
                        session.getUpstream().getSession().getPeer().getChannel().eventLoop().execute(() -> session.sendUpstreamPacket(diagnostic));
                    sender.sendMessage("Give diagnostic matrix sent; ncfdiag full restores the native tree");
                } catch (ReflectiveOperationException | RuntimeException e) {
                    getLogger().log(java.util.logging.Level.WARNING, "Unable to read diagnostic baseline", e);
                    sender.sendMessage("No diagnostic baseline available");
                }
                return true;
            }
            if (Set.of("enum", "soft", "string", "message", "raw", "combined").contains(args[0])) {
                if (GeyserApi.api().connectionByUuid(target.getUniqueId()) instanceof GeyserSession session) {
                    CommandParamData parameter = new CommandParamData();
                    parameter.setName("option"); parameter.setOptional(true);
                    if (Set.of("enum", "soft", "combined").contains(args[0])) {
                        Map<String, Set<CommandEnumConstraint>> entries = new LinkedHashMap<>();
                        List.of("delete", "set", "list").forEach(value -> entries.put(value, Set.of()));
                        parameter.setEnumData(new CommandEnumData("ncf_diagnostic_choices", entries, !args[0].equals("enum")));
                    } else parameter.setType(args[0].equals("message") ? CommandParam.MESSAGE
                            : args[0].equals("raw") ? CommandParam.TEXT : CommandParam.STRING);
                    List<CommandOverloadData> overloads = new ArrayList<>();
                    overloads.add(new CommandOverloadData(false, new CommandParamData[]{parameter}));
                    if (args[0].equals("combined")) {
                        CommandParamData tail = new CommandParamData(); tail.setName("args");
                        tail.setType(CommandParam.TEXT); tail.setOptional(true);
                        overloads.add(new CommandOverloadData(false, new CommandParamData[]{tail}));
                    }
                    AvailableCommandsPacket packet = new AvailableCommandsPacket();
                    packet.getCommands().add(new CommandData("ncf", "Isolated " + args[0] + " diagnostic",
                            Set.of(CommandData.Flag.NOT_CHEAT), CommandPermission.ANY, null, List.of(),
                            overloads.toArray(CommandOverloadData[]::new)));
                    session.getUpstream().getSession().getPeer().getChannel().eventLoop().execute(() -> session.sendUpstreamPacket(packet));
                    sender.sendMessage("Isolated /ncf definition: " + args[0]);
                }
                return true;
            }
            if (args[0].equals("member") || args[0].equals("operator")) {
                if (GeyserApi.api().connectionByUuid(target.getUniqueId()) instanceof GeyserSession session) {
                    boolean member = args[0].equals("member");
                    session.getUpstream().getSession().getPeer().getChannel().eventLoop().execute(() -> {
                        int actual = session.getOpPermissionLevel();
                        if (member) session.setOpPermissionLevel(0);
                        try { session.sendAdventureSettings(); }
                        finally { session.setOpPermissionLevel(actual); }
                    });
                    sender.sendMessage("Bedrock role diagnostic: " + args[0] + "; Paper OP unchanged");
                }
                return true;
            }
            if (args[0].equals("minimal")) minimal.add(target.getUniqueId());
            else { minimal.remove(target.getUniqueId()); diagnosticBaselines.remove(target.getUniqueId()); }
            target.updateCommands();
            sender.sendMessage("Command packet mode: " + args[0] + " for " + target.getName());
            return true;
        });
        var cmd = Objects.requireNonNull(getCommand("ncf"));
        cmd.setTabCompleter((sender, command, alias, args) -> {
            if (args.length == 1) return List.of("delete", "set", "list").stream().filter(s -> s.startsWith(args[0])).toList();
            if (args.length == 2 && args[0].equals("delete") && sender instanceof org.bukkit.entity.Player p)
                return homes.computeIfAbsent(p.getUniqueId(), id -> new ArrayList<>(List.of(p.getName() + "_base")))
                        .stream().filter(s -> s.startsWith(args[1])).toList();
            return List.of();
        });
        cmd.setExecutor((sender, command, alias, args) -> {
            if (!(sender instanceof org.bukkit.entity.Player p)) return true;
            var list = homes.computeIfAbsent(p.getUniqueId(), id -> new ArrayList<>(List.of(p.getName() + "_base")));
            if (args.length >= 2 && args[0].equals("set")) list.add(args[1]);
            if (args.length >= 2 && args[0].equals("delete")) list.remove(args[1]);
            sender.sendMessage("fixture:" + String.join(",", list)); return true;
        });
    }
    private AvailableCommandsPacket nativeBaseline(UUID uuid) throws ReflectiveOperationException {
        var plugin = getServer().getPluginManager().getPlugin("GeyserNativeCompletions");
        var statesField = plugin.getClass().getDeclaredField("states"); statesField.setAccessible(true);
        var state = ((Map<?, ?>) statesField.get(plugin)).get(uuid);
        var baselineField = state.getClass().getDeclaredField("baseline"); baselineField.setAccessible(true);
        return (AvailableCommandsPacket) ((java.util.concurrent.atomic.AtomicReference<?>) baselineField.get(state)).get();
    }
    private static CommandData named(CommandData original, String label) {
        return new CommandData(label, original.getDescription(), original.getFlags(), original.getPermission(),
                null, original.getSubcommands(), original.getOverloads());
    }
    private static CommandData giveProbe(CommandData original, String label, int mode) {
        CommandOverloadData[] overloads = new CommandOverloadData[original.getOverloads().length];
        for (int i = 0; i < overloads.length; i++) {
            var old = original.getOverloads()[i];
            CommandParamData[] params = new CommandParamData[old.getOverloads().length];
            for (int j = 0; j < params.length; j++) {
                var p = old.getOverloads()[j];
                var copy = new CommandParamData(); copy.setName(p.getName()); copy.setOptional(p.isOptional());
                copy.setType(p.getType()); copy.setPostfix(p.getPostfix()); copy.getOptions().addAll(p.getOptions());
                var data = p.getEnumData();
                copy.setEnumData(data == null || mode == 0 ? data :
                        new CommandEnumData("ncf_diag_" + label + "_" + i + "_" + j, data.getValues(), mode == 2));
                params[j] = copy;
            }
            overloads[i] = new CommandOverloadData(old.isChaining(), params);
        }
        return new CommandData(label, "Give parser diagnostic", original.getFlags(), original.getPermission(),
                null, original.getSubcommands(), overloads);
    }
    @Override public void onDisable() { GeyserApi.api().eventBus().unregisterAll(registrar); }
}
