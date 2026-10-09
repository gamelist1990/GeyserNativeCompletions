package com.pexserver.completions;

import io.netty.channel.Channel;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.cloudburstmc.protocol.bedrock.data.command.CommandData;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.session.GeyserSession;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import java.util.logging.Level;
import static com.pexserver.completions.CompletionModel.*;

/** Same-server Paper + Geyser-Spigot bridge; intentionally does not execute commands. */
public final class NativeCompletionsPlugin extends JavaPlugin implements Listener {
    private static final String HANDLER = "pex-native-completions";
    private final Map<UUID, State> states = new LinkedHashMap<>();
    private final Map<GeyserSession, State> sessions = new ConcurrentHashMap<>();
    private final EventRegistrar registrar = new EventRegistrar() {};
    private BukkitTask ticker;
    private Settings settings;
    private long tick;
    private int rotation;
    private boolean stopping;
    private PaperSuggestions javaSuggestions;

    @Override public void onEnable() {
        saveDefaultConfig(); settings = readSettings();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("gnc")).setExecutor(this::admin);
        Objects.requireNonNull(getCommand("gnc")).setTabCompleter((sender, cmd, alias, args) ->
                args.length == 1 ? List.of("refresh", "reload", "status").stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList() : List.of());
        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        // Geyser starts its event bus during ServerLoadEvent, after plugin enable callbacks.
        Bukkit.getScheduler().runTask(this, () -> {
            try { javaSuggestions = new PaperSuggestions(getServer()); }
            catch (ReflectiveOperationException | LinkageError e) {
                getLogger().log(Level.WARNING, "Java argument suggestions unavailable on this Paper version", e);
            }
            if (!stopping) GeyserApi.api().eventBus().subscribe(registrar, SessionLoginEvent.class, event -> {
                if (event.connection() instanceof GeyserSession session) install(session);
            });
        });
        getLogger().info("Native Bedrock argument completion enabled (Paper + Geyser-Spigot).");
    }
    @Override public void onDisable() {
        stopping = true;
        GeyserApi.api().eventBus().unregisterAll(registrar);
        if (ticker != null) ticker.cancel();
        for (State state : List.copyOf(sessions.values())) detach(state);
        states.clear(); sessions.clear();
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(this, () -> attach(event.getPlayer()));
        dirtyAll();
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        State state = states.remove(event.getPlayer().getUniqueId());
        if (state != null) detach(state);
        dirtyAll();
    }
    private void dirtyAll() { states.values().forEach(s -> s.nextRefresh = 0); }

    private void attach(Player player) {
        if (stopping || !player.isOnline() || states.containsKey(player.getUniqueId())) return;
        GeyserConnection connection = GeyserApi.api().connectionByUuid(player.getUniqueId());
        if (!(connection instanceof GeyserSession session) || session.getUpstream().getSession().isSubClient()) return;
        State state = install(session);
        if (state == null) return;
        state.uuid = player.getUniqueId();
        states.put(state.uuid, state);
        // Already-online players may have sent their initial tree before this plugin was enabled.
        if (state.baseline.get() == null) player.updateCommands();
    }
    private State install(GeyserSession session) {
        if (stopping || session.getUpstream().getSession().isSubClient()) return null;
        Channel channel = session.getUpstream().getSession().getPeer().getChannel();
        if (!channel.isActive()) return null;
        return sessions.computeIfAbsent(session, ignored -> createState(session, channel));
    }
    private State createState(GeyserSession session, Channel channel) {
        State state = new State(session, channel);
        state.hook = new CommandPacketInterceptor(() -> state.models, packet -> {
            state.baseline.set(packet); state.revision.incrementAndGet();
        }, error -> {
            if (state.hookFailed.compareAndSet(false, true))
                getLogger().log(Level.WARNING, "Geyser packet hook failed; preserving original command packets", error);
        });
        Runnable install = () -> {
            if (!channel.isActive() || !state.live.get()) return;
            if (channel.pipeline().get(HANDLER) != null) {
                state.hookFailed.set(true); return;
            }
            channel.pipeline().addLast(HANDLER, state.hook);
        };
        if (channel.eventLoop().inEventLoop()) install.run();
        else channel.eventLoop().execute(install);
        channel.closeFuture().addListener(ignored -> {
            state.live.set(false);
            sessions.remove(session, state);
        });
        return state;
    }
    private void detach(State state) {
        state.live.set(false); state.models = Map.of(); state.round = null;
        sessions.remove(state.session, state);
        if (state.channel.eventLoop().isShuttingDown()) return;
        state.channel.eventLoop().execute(() -> {
            try {
                AvailableCommandsPacket baseline = state.baseline.get();
                if (state.channel.isActive() && baseline != null && state.channel.pipeline().get(HANDLER) == state.hook) {
                    AvailableCommandsPacket restore = PacketComposer.copy(baseline);
                    state.hook.markGenerated(restore);
                    state.session.sendUpstreamPacketImmediately(restore);
                }
            } finally {
                if (state.channel.pipeline().get(HANDLER) == state.hook) state.channel.pipeline().remove(state.hook);
            }
        });
    }
    private void tick() {
        tick++;
        if (tick % 20 == 1) for (Player player : Bukkit.getOnlinePlayers()) attach(player);
        List<State> active = new ArrayList<>(states.values());
        if (active.isEmpty()) return;
        for (State state : active) {
            Player player = Bukkit.getPlayer(state.uuid);
            if (player == null || !player.isOnline() || !state.channel.isActive() || state.hookFailed.get()) {
                states.remove(state.uuid); detach(state); continue;
            }
            AvailableCommandsPacket baseline = state.baseline.get();
            if (baseline == null) continue;
            long revision = state.revision.get();
            if (state.round != null && state.round.revision != revision) state.round = null;
            if (state.round == null && (state.seenRevision != revision || tick >= state.nextRefresh)) {
                state.round = begin(state, player, baseline, revision);
                state.seenRevision = revision;
                // Immediately withdraw removed/inaccessible command models.
                Map<String, CompletionModel.Command> retained = new LinkedHashMap<>(state.models);
                retained.keySet().retainAll(state.round.labels);
                publish(state, Map.copyOf(retained));
            }
        }
        active.removeIf(s -> !s.live.get() || s.round == null);
        if (active.isEmpty()) return;
        long deadline = System.nanoTime() + settings.budgetNanos;
        int remaining = settings.queries;
        int cursor = Math.floorMod(rotation++, active.size());
        // Rotate between players after each provider call, keeping one player from monopolizing the budget.
        while (remaining-- > 0 && System.nanoTime() < deadline && !active.isEmpty()) {
            State state = active.get(cursor % active.size());
            advance(state);
            if (state.round == null) active.remove(state); else cursor++;
        }
    }
    private Round begin(State state, Player player, AvailableCommandsPacket baseline, long revision) {
        Round round = new Round(revision);
        NativeArgumentScan.Provider nativeProvider = null;
        if (javaSuggestions != null) {
            try { nativeProvider = javaSuggestions.forPlayer(player); }
            catch (ReflectiveOperationException e) { getLogger().log(Level.WARNING, "Cannot read player's Java command source", e); }
        }
        for (CommandData data : baseline.getCommands()) {
            String label = data.getName().toLowerCase(Locale.ROOT);
            boolean generic = PacketComposer.generic(data);
            CommandData nativeData = JavaArgumentHints.gameModes(data);
            if ((!generic && (nativeProvider == null || !NativeArgumentScan.supported(nativeData))) || settings.excluded.contains(label) ||
                    (!settings.included.isEmpty() && !settings.included.contains(label))) continue;
            org.bukkit.command.Command command = Bukkit.getCommandMap().getCommand(label);
            if (command == null || !command.testPermissionSilent(player) ||
                    state.cooldowns.getOrDefault(label, 0L) > tick) continue;
            if (round.labels.size() >= settings.maxCommands) break;
            if (!round.labels.add(label)) continue;
            if (!generic) {
                var delegate = nativeProvider;
                NativeArgumentScan.Provider bounded = (input, argument) -> {
                    if (!player.isOnline() || !command.testPermissionSilent(player))
                        return java.util.concurrent.CompletableFuture.completedFuture(List.of());
                    long start = System.nanoTime();
                    var future = delegate.complete(input, argument);
                    if (System.nanoTime() - start > settings.slowNanos) throw new SlowCompleterException(label);
                    return future;
                };
                round.pending.add(new ScanTask(label, new NativeArgumentScan(nativeData, settings.limits, bounded)));
                continue;
            }
            Provider provider = (prefix, partial) -> {
                if (!player.isOnline() || !command.testPermissionSilent(player)) return List.of();
                String[] args = new String[prefix.size() + 1];
                for (int i = 0; i < prefix.size(); i++) args[i] = prefix.get(i);
                args[prefix.size()] = partial;
                long start = System.nanoTime();
                List<String> values = command.tabComplete(player, label, args);
                if (System.nanoTime() - start > settings.slowNanos)
                    throw new SlowCompleterException(label);
                return values;
            };
            CompletionScan scan = new CompletionScan(label, settings.limits, provider,
                    settings.prefixProbes, settings.extra.getOrDefault(label, List.of()));
            round.pending.add(new ScanTask(label, scan));
        }
        return round;
    }
    private void advance(State state) {
        Round round = state.round;
        if (round == null) return;
        ScanTask task = round.pending.peek();
        if (task != null) {
            try {
                task.scan.step();
                if (task.scan.done()) {
                    CompletionModel.Command raw = task.scan.result();
                    List<Node> bounded = new ArrayList<>();
                    for (Node node : raw.nodes()) {
                        if (round.nodes >= settings.totalNodes || round.values >= settings.totalValues) break;
                        List<String> values = node.candidates().subList(0,
                                Math.min(node.candidates().size(), settings.totalValues - round.values));
                        bounded.add(new Node(node.prefix(), values)); round.nodes++; round.values += values.size();
                    }
                    List<Argument> arguments = new ArrayList<>();
                    for (Argument argument : raw.arguments()) {
                        if (round.nodes >= settings.totalNodes || round.values >= settings.totalValues) break;
                        List<String> values = argument.candidates().subList(0,
                                Math.min(argument.candidates().size(), settings.totalValues - round.values));
                        arguments.add(new Argument(argument.key(), values)); round.nodes++; round.values += values.size();
                    }
                    CompletionModel.Command model = new CompletionModel.Command(raw.label(), bounded, arguments);
                    if (model.hasCandidates()) round.completed.put(task.label, model);
                    round.pending.remove();
                }
            } catch (RuntimeException e) {
                state.cooldowns.put(task.label, tick + 1200);
                round.pending.remove(); round.labels.remove(task.label);
                getLogger().warning("Skipping completer /" + task.label + " for 60s: " + e.getClass().getSimpleName());
            }
        }
        if (round.pending.isEmpty()) {
            if (round.revision == state.revision.get()) publish(state, Map.copyOf(round.completed));
            state.round = null; state.nextRefresh = tick + settings.refresh;
        }
    }
    private void publish(State state, Map<String, CompletionModel.Command> next) {
        Map<String, CompletionModel.Command> previous = state.models;
        if (previous.equals(next)) return;
        AvailableCommandsPacket baseline = state.baseline.get();
        if (baseline == null) return;
        // Snapshot replacement and outgoing writes are ordered on the same event loop as incoming baselines.
        state.channel.eventLoop().execute(() -> {
            if (!state.live.get() || !state.channel.isActive()) return;
            Map<String, CompletionModel.Command> current = state.models;
            state.models = next;
            if (PacketComposer.sameStructure(current, next)) {
                for (CompletionModel.Command model : next.values()) {
                    Map<List<String>, List<String>> old = current.get(model.label()).contexts();
                    for (Node node : model.nodes()) if (!node.candidates().equals(old.get(node.prefix())))
                        state.session.sendUpstreamPacket(PacketComposer.replace(model.label(), node));
                    var oldArguments = current.get(model.label()).argumentContexts();
                    for (Argument argument : model.arguments()) if (!argument.candidates().equals(oldArguments.get(argument.key())))
                        state.session.sendUpstreamPacket(PacketComposer.replace(model.label(), argument));
                }
            } else {
                AvailableCommandsPacket updated = PacketComposer.patch(state.baseline.get(), next);
                state.hook.markGenerated(updated);
                try { state.session.sendUpstreamPacket(updated); }
                catch (RuntimeException e) { state.hook.unmarkGenerated(updated); throw e; }
            }
        });
    }
    private boolean admin(CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> { reloadConfig(); settings = readSettings(); states.values().forEach(s -> s.round = null); dirtyAll(); sender.sendMessage("補完設定を再読み込みした。"); }
            case "refresh" -> { states.values().forEach(s -> s.round = null); dirtyAll(); sender.sendMessage("Bedrockの補完候補を再取得する。"); }
            case "status" -> {
                int commands = states.values().stream().mapToInt(s -> s.models.size()).sum();
                int nodes = states.values().stream().flatMap(s -> s.models.values().stream()).mapToInt(m -> m.nodes().size() + m.arguments().size()).sum();
                sender.sendMessage("Bedrock接続: " + states.size() + " / 補完コマンド: " + commands + " / 引数候補リスト: " + nodes);
            }
            default -> { return false; }
        }
        return true;
    }
    private Settings readSettings() {
        var c = getConfig();
        Map<String, List<List<String>>> extra = new HashMap<>();
        var section = c.getConfigurationSection("extra-contexts");
        if (section != null) for (String label : section.getKeys(false)) {
            List<List<String>> contexts = section.getStringList(label).stream().limit(32)
                    .map(s -> s.strip().isEmpty() ? List.<String>of() : List.of(s.strip().split("\\s+"))).toList();
            extra.put(label.toLowerCase(Locale.ROOT), contexts);
        }
        return new Settings(clamp(c.getInt("refresh-ticks", 100), 20, 12000),
                clamp(c.getInt("queries-per-tick", 8), 1, 128),
                nanos(c.getDouble("budget-millis-per-tick", 2), 0.1, 10),
                nanos(c.getDouble("slow-query-millis", 10), 1, 1000),
                new Limits(clamp(c.getInt("max-depth", 3), 1, 8),
                        clamp(c.getInt("max-nodes-per-command", 32), 1, 256),
                        clamp(c.getInt("max-candidates-per-node", 64), 1, 512),
                        clamp(c.getInt("max-branches-per-command", 64), 1, 256),
                        clamp(c.getInt("max-candidate-length", 96), 1, 256)),
                clamp(c.getInt("max-commands-per-player", 128), 1, 1024),
                clamp(c.getInt("max-total-nodes-per-player", 256), 1, 4096),
                clamp(c.getInt("max-total-candidates-per-player", 4096), 1, 65536),
                names(c.getStringList("include-commands")), names(c.getStringList("exclude-commands")),
                c.getStringList("prefix-probes").stream().filter(s -> !s.isBlank() && s.length() <= 16).limit(16).toList(),
                Map.copyOf(extra));
    }
    private static Set<String> names(List<String> values) {
        Set<String> result = new HashSet<>(); values.forEach(s -> result.add(s.toLowerCase(Locale.ROOT))); return Set.copyOf(result);
    }
    private static int clamp(int n, int lo, int hi) { return Math.max(lo, Math.min(hi, n)); }
    private static long nanos(double n, double lo, double hi) {
        if (!Double.isFinite(n)) n = lo;
        return (long) (Math.max(lo, Math.min(hi, n)) * 1_000_000);
    }
    private record Settings(int refresh, int queries, long budgetNanos, long slowNanos, Limits limits,
                            int maxCommands, int totalNodes, int totalValues, Set<String> included, Set<String> excluded,
                            List<String> prefixProbes, Map<String, List<List<String>>> extra) {}
    private static final class State {
        UUID uuid; final GeyserSession session; final Channel channel;
        final AtomicReference<AvailableCommandsPacket> baseline = new AtomicReference<>();
        final AtomicLong revision = new AtomicLong();
        final AtomicBoolean live = new AtomicBoolean(true), hookFailed = new AtomicBoolean();
        final Map<String, Long> cooldowns = new HashMap<>();
        volatile Map<String, CompletionModel.Command> models = Map.of();
        CommandPacketInterceptor hook; Round round; long seenRevision = -1, nextRefresh;
        State(GeyserSession session, Channel channel) { this.session = session; this.channel = channel; }
    }
    private static final class Round {
        final long revision; final Set<String> labels = new LinkedHashSet<>();
        final Queue<ScanTask> pending = new ArrayDeque<>();
        final Map<String, CompletionModel.Command> completed = new LinkedHashMap<>();
        int nodes, values;
        Round(long revision) { this.revision = revision; }
    }
    private record ScanTask(String label, CompletionTask scan) {}
    private static final class SlowCompleterException extends RuntimeException {
        SlowCompleterException(String label) { super(label); }
    }
}
