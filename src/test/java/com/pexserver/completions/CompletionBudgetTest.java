package com.pexserver.completions;

import java.util.*;
import org.junit.jupiter.api.Test;
import static com.pexserver.completions.CompletionModel.*;
import static org.junit.jupiter.api.Assertions.*;

class CompletionBudgetTest {
    @Test void largeOverloadTreeCannotSuppressPluginAndSmallNativeCommands() {
        List<String> many = java.util.stream.IntStream.range(0, 64).mapToObj(i -> "value" + i).toList();
        var large = new Command("loot", List.of(), java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> new Argument(new ArgumentKey(i, 1, "table"), many)).toList());
        var damage = new Command("damage", List.of(), List.of(new Argument(new ArgumentKey(0, 2, "damageType"), List.of("fall", "generic"))));
        var plugin = new Command("ncf", List.of(new Node(List.of(), List.of("delete", "set", "list"))));
        var result = CompletionBudget.bound(new LinkedHashMap<>(Map.of("loot", large, "damage", damage, "ncf", plugin)), 32, 256, 10);
        assertEquals(plugin, result.get("ncf")); assertEquals(damage, result.get("damage"));
        assertEquals(5, result.get("loot").arguments().getFirst().candidates().size());
        assertEquals(10, result.values().stream().mapToInt(c -> c.nodes().stream().mapToInt(n -> n.candidates().size()).sum()
                + c.arguments().stream().mapToInt(a -> a.candidates().size()).sum()).sum());
    }
    @Test void nativeArgumentListsAlsoObeyThePerCommandAndGlobalNodeCaps() {
        var raw = new Command("native", List.of(), java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> new Argument(new ArgumentKey(i, 1, "choice"), List.of("value"))).toList());
        assertEquals(32, CompletionBudget.bound(Map.of("native", raw), 32, 256, 4096).get("native").arguments().size());
        assertEquals(4, CompletionBudget.bound(Map.of("native", raw), 32, 4, 4096).get("native").arguments().size());
    }
}
