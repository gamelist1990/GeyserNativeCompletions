package com.pexserver.completions;

import java.util.*;
import static com.pexserver.completions.CompletionModel.*;

/** Large native overload trees cannot consume the whole budget before smaller commands. */
final class CompletionBudget {
    private CompletionBudget() {}
    static Map<String, Command> bound(Map<String, Command> input, int perCommand, int maxNodes, int maxValues) {
        List<Command> commands = new ArrayList<>(input.values());
        commands.sort(Comparator.comparing((Command c) -> c.nodes().isEmpty())
                .thenComparingInt(c -> cost(c, perCommand)).thenComparing(Command::label));
        Map<String, Command> result = new LinkedHashMap<>();
        int nodes = 0, values = 0;
        for (Command command : commands) {
            List<Node> boundedNodes = new ArrayList<>();
            List<Argument> boundedArguments = new ArrayList<>();
            int local = 0;
            for (Node node : command.nodes()) {
                if (local >= perCommand || nodes >= maxNodes || values >= maxValues) break;
                var candidates = node.candidates().subList(0, Math.min(node.candidates().size(), maxValues - values));
                boundedNodes.add(new Node(node.prefix(), candidates));
                local++; nodes++; values += candidates.size();
            }
            for (Argument argument : command.arguments()) {
                if (local >= perCommand || nodes >= maxNodes || values >= maxValues) break;
                var candidates = argument.candidates().subList(0, Math.min(argument.candidates().size(), maxValues - values));
                boundedArguments.add(new Argument(argument.key(), candidates));
                local++; nodes++; values += candidates.size();
            }
            var bounded = new Command(command.label(), boundedNodes, boundedArguments);
            if (bounded.hasCandidates()) result.put(command.label(), bounded);
        }
        return Map.copyOf(result);
    }
    private static int cost(Command command, int perCommand) {
        return java.util.stream.Stream.concat(command.nodes().stream().map(Node::candidates),
                command.arguments().stream().map(Argument::candidates)).limit(perCommand).mapToInt(List::size).sum();
    }
}
