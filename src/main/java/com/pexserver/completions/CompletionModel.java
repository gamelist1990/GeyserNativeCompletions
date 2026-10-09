package com.pexserver.completions;

import java.util.*;

/** Immutable data crossing the Bukkit / Netty thread boundary. */
public final class CompletionModel {
    private CompletionModel() {}
    public record Node(List<String> prefix, List<String> candidates) {
        public Node { prefix = List.copyOf(prefix); candidates = List.copyOf(candidates); }
    }
    public record ArgumentKey(int overload, int parameter, String name) {}
    public record Argument(ArgumentKey key, List<String> candidates) {
        public Argument { candidates = List.copyOf(candidates); }
    }
    public record Command(String label, List<Node> nodes, List<Argument> arguments) {
        public Command { nodes = List.copyOf(nodes); arguments = List.copyOf(arguments); }
        public Command(String label, List<Node> nodes) { this(label, nodes, List.of()); }
        public boolean hasCandidates() { return !nodes.isEmpty() || !arguments.isEmpty(); }
        public Map<List<String>, List<String>> contexts() {
            Map<List<String>, List<String>> result = new LinkedHashMap<>();
            nodes.forEach(n -> result.put(n.prefix(), n.candidates()));
            return Collections.unmodifiableMap(result);
        }
        public Map<ArgumentKey, List<String>> argumentContexts() {
            Map<ArgumentKey, List<String>> result = new LinkedHashMap<>();
            arguments.forEach(a -> result.put(a.key(), a.candidates()));
            return Collections.unmodifiableMap(result);
        }
    }
    public record Limits(int depth, int nodes, int candidates, int branches, int wordLength) {}
    @FunctionalInterface public interface Provider {
        List<String> complete(List<String> prefix, String partial);
    }
    static boolean validWord(String s, int maxLength) {
        if (s == null || s.isEmpty() || s.length() > maxLength) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '"' || c == '\\' || c == '§') return false;
        }
        return true;
    }
}
