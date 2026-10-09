package com.pexserver.completions;

import org.cloudburstmc.protocol.bedrock.data.command.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static com.pexserver.completions.CompletionModel.*;

/** Samples existing overload paths, retaining their typed arguments and literal branches. */
final class NativeArgumentScan implements CompletionTask {
    @FunctionalInterface interface Provider {
        CompletableFuture<List<String>> complete(String input, String argument);
    }
    private record Path(int overload, int parameter, String prefix) {}
    private final String label;
    private final CommandData command;
    private final Limits limits;
    private final Provider provider;
    private final Queue<Path> paths = new ArrayDeque<>();
    private final Map<ArgumentKey, LinkedHashSet<String>> values = new LinkedHashMap<>();
    private Path waiting;
    private CompletableFuture<List<String>> future;
    private long expires;
    private int calls, branches;

    NativeArgumentScan(CommandData command, Limits limits, Provider provider) {
        this.command = command; this.label = command.getName().toLowerCase(Locale.ROOT);
        this.limits = limits; this.provider = provider;
        for (int i = 0; i < command.getOverloads().length && i < limits.nodes(); i++)
            paths.add(new Path(i, 0, label + " "));
    }
    static boolean supported(CommandData command) {
        for (var overload : command.getOverloads())
            for (var argument : overload.getOverloads()) if (queryable(argument)) return true;
        return false;
    }
    private static boolean queryable(CommandParamData p) { return p.getEnumData() == null && p.getType() == CommandParam.STRING; }
    @Override public boolean done() { return waiting == null && paths.isEmpty(); }
    @Override public void step() {
        if (waiting != null) {
            if (!future.isDone() && System.nanoTime() < expires) return;
            List<String> found = future.isDone() && !future.isCompletedExceptionally() ? future.getNow(List.of()) : List.of();
            var p = parameter(waiting);
            var key = new ArgumentKey(waiting.overload, waiting.parameter, p.getName());
            var candidates = values.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
            for (String s : found) {
                if (candidates.size() >= limits.candidates()) break;
                if (validWord(s, limits.wordLength())) candidates.add(s);
            }
            // A representative prior value lets later arguments be queried without enumerating a registry's Cartesian product.
            next(waiting, candidates.isEmpty() ? List.of("word") : List.of(candidates.iterator().next()));
            waiting = null; future = null;
            return;
        }
        if (paths.isEmpty()) return;
        Path path = paths.remove();
        CommandParamData[] args = command.getOverloads()[path.overload].getOverloads();
        if (path.parameter >= args.length) return;
        var p = args[path.parameter];
        if (queryable(p) && calls < limits.nodes()) {
            calls++;
            future = Objects.requireNonNull(provider.complete(path.prefix, p.getName()));
            waiting = path; expires = System.nanoTime() + 2_000_000_000L;
        } else next(path, samples(p));
    }
    private CommandParamData parameter(Path p) { return command.getOverloads()[p.overload].getOverloads()[p.parameter]; }
    private void next(Path path, List<String> samples) {
        int next = path.parameter + 1;
        if (next >= command.getOverloads()[path.overload].getOverloads().length) return;
        int count = 0;
        for (String sample : samples) {
            if (count++ > 0 && branches++ >= limits.branches()) break;
            paths.add(new Path(path.overload, next, path.prefix + sample + " "));
        }
    }
    private List<String> samples(CommandParamData p) {
        if (p.getEnumData() != null) {
            var words = p.getEnumData().getValues().keySet().stream().filter(s -> validWord(s, limits.wordLength())).toList();
            // Explore small literal branches, sample only one value from large registry enums.
            return words.subList(0, Math.min(words.size(), words.size() <= 4 ? words.size() : 1));
        }
        CommandParam type = p.getType();
        if (type == CommandParam.TARGET) return List.of("@s");
        if (type == CommandParam.INT || type == CommandParam.FLOAT || type == CommandParam.VALUE) return List.of("1");
        if (type == CommandParam.POSITION || type == CommandParam.BLOCK_POSITION) return List.of("~ ~ ~");
        if (type == CommandParam.JSON) return List.of("{}");
        if (type == CommandParam.STRING || type == CommandParam.FILE_PATH) return List.of("word");
        return List.of(); // Never guess a complex grammar such as a nested command or free text.
    }
    @Override public Command result() {
        if (!done()) throw new IllegalStateException("Scan is still running");
        return new Command(label, List.of(), values.entrySet().stream().filter(e -> !e.getValue().isEmpty())
                .map(e -> new Argument(e.getKey(), List.copyOf(e.getValue()))).toList());
    }
}
