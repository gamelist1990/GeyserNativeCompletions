package com.pexserver.completions;

import java.util.*;
import static com.pexserver.completions.CompletionModel.*;

/** One step makes at most one provider call. No unbounded recursive probing. */
public final class CompletionScan implements CompletionTask {
    private final String label;
    private final Limits limits;
    private final Provider provider;
    private final List<String> probes;
    private final Queue<List<String>> pending = new ArrayDeque<>();
    private final Set<List<String>> visited = new HashSet<>();
    private final Map<List<String>, List<String>> discovered = new LinkedHashMap<>();
    private List<String> current;
    private final LinkedHashSet<String> currentValues = new LinkedHashSet<>();
    private int probeIndex;
    private boolean probing;
    private int calls;

    public CompletionScan(String label, Limits limits, Provider provider,
                          List<String> probes, List<List<String>> extraContexts) {
        this.label = label; this.limits = limits; this.provider = provider;
        this.probes = List.copyOf(probes);
        enqueue(List.of());
        for (List<String> p : extraContexts) {
            if (p.stream().allMatch(this::validWord)) enqueue(List.copyOf(p));
        }
    }
    private void enqueue(List<String> prefix) {
        if (prefix.size() >= limits.depth() || visited.size() >= limits.nodes()) return;
        if (visited.add(prefix)) pending.add(prefix);
    }
    public boolean done() { return current == null && pending.isEmpty(); }
    public int calls() { return calls; }
    public void step() {
        if (done()) return;
        if (current == null) {
            current = pending.remove(); currentValues.clear(); probeIndex = 0; probing = false;
        }
        String partial = probing ? probes.get(probeIndex++) : "";
        List<String> returned = provider.complete(current, partial);
        calls++;
        if (returned != null) for (String s : returned) {
            if (currentValues.size() >= limits.candidates()) break;
            if (validWord(s)) currentValues.add(s);
        }
        // Only probe prefixes when the empty-input query yielded no candidates.
        if (!probing && currentValues.isEmpty() && !probes.isEmpty()) {
            probing = true; return;
        }
        if (probing && probeIndex < probes.size()) return;
        List<String> values = List.copyOf(currentValues);
        // Many Bukkit completers return the same list at every depth. Stop that expansion.
        boolean repeats = false;
        for (int i = 0; i < current.size(); i++) {
            if (values.equals(discovered.get(current.subList(0, i)))) { repeats = true; break; }
        }
        if (!values.isEmpty() && !repeats) {
            discovered.put(current, values);
            int count = 0;
            for (String s : values) {
                if (count++ >= limits.branches()) break;
                List<String> child = new ArrayList<>(current); child.add(s);
                enqueue(List.copyOf(child));
            }
        }
        current = null;
    }
    private boolean validWord(String s) {
        return CompletionModel.validWord(s, limits.wordLength());
    }
    public Command result() {
        if (!done()) throw new IllegalStateException("Scan is still running");
        return new Command(label, discovered.entrySet().stream().map(e -> new Node(e.getKey(), e.getValue())).toList());
    }
}
