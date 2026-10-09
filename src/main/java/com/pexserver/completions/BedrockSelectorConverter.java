package com.pexserver.completions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Conservative, text-only conversion of Bedrock selector keys to Java Brigadier syntax.
 * Java-only selectors are unchanged. No inventory / family emulation is attempted.
 */
final class BedrockSelectorConverter {
    private static final Set<String> KEYS = Set.of(
            "r", "rm", "c", "m", "l", "lm", "rx", "rxm", "ry", "rym");
    private static final Set<String> NOT_PORTABLE = Set.of("family", "hasitem", "haspermission", "has_property");
    private static final Pattern NON_NEGATIVE = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern SIGNED = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern INTEGER = Pattern.compile("[0-9]+");
    private static final int MAX_INPUT = 32767;
    private static final Pattern TEXT_AFTER_EXECUTE = Pattern.compile(
            "(?i)\\s+run\\s+(?:minecraft:)?(?:say|me|msg|tell|w|whisper|teammsg|tm|tellraw|title)(?=\\s|$)");

    private BedrockSelectorConverter() {}

    static String rewrite(String command) {
        if (command == null || command.length() > MAX_INPUT || command.indexOf('@') < 0) return command;
        // /execute ... run say <text> may contain selector-looking literal text.
        Matcher textTail = TEXT_AFTER_EXECUTE.matcher(command);
        if (textTail.find()) {
            int cut = textTail.start();
            return rewriteSelectors(command.substring(0, cut)) + command.substring(cut);
        }
        return rewriteSelectors(command);
    }

    private static String rewriteSelectors(String command) {
        StringBuilder out = new StringBuilder(command.length() + 32);
        boolean changed = false;
        char quote = 0;
        for (int i = 0; i < command.length();) {
            char ch = command.charAt(i);
            if (ch == '\\' && i + 1 < command.length()) {
                out.append(command, i, i + 2);
                i += 2;
                continue;
            }
            if (quote != 0) {
                out.append(ch);
                if (ch == quote) quote = 0;
                i++;
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                out.append(ch);
                i++;
                continue;
            }
            if (ch != '@' || i + 2 >= command.length() || !baseSelector(command.charAt(i + 1))
                    || command.charAt(i + 2) != '['
                    || (i > 0 && (Character.isLetterOrDigit(command.charAt(i - 1))
                            || command.charAt(i - 1) == '_' || command.charAt(i - 1) == '@'))) {
                out.append(ch);
                i++;
                continue;
            }
            int end = selectorEnd(command, i + 2);
            if (end < 0) {
                out.append(ch);
                i++;
                continue;
            }
            String before = command.substring(i, end + 1);
            String after = selector(command.charAt(i + 1), command.substring(i + 3, end), before);
            out.append(after);
            changed |= !before.equals(after);
            i = end + 1;
        }
        return changed ? out.toString() : command;
    }

    private static boolean baseSelector(char base) {
        return base == 'a' || base == 'e' || base == 'p' || base == 'r' || base == 's';
    }

    private static int selectorEnd(String text, int opening) {
        int square = 1, curly = 0;
        char quote = 0;
        for (int i = opening + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) { i++; continue; }
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == '{') curly++;
            else if (c == '}') { if (--curly < 0) return -1; }
            else if (c == '[') square++;
            else if (c == ']') {
                if (--square == 0) return curly == 0 ? i : -1;
                if (square < 0) return -1;
            }
        }
        return -1;
    }

    private static String selector(char base, String content, String original) {
        List<String> entries = split(content);
        if (entries == null) return original;
        List<String> preserved = new ArrayList<>();
        Map<String, String> aliases = new HashMap<>();
        Set<String> present = new HashSet<>();
        for (String entry : entries) {
            int equals = topEquals(entry);
            if (equals < 1) return original;
            String key = entry.substring(0, equals).trim();
            String value = entry.substring(equals + 1).trim();
            if (value.isEmpty() || key.isEmpty()) return original;
            if (NOT_PORTABLE.contains(key)) return original;
            if (KEYS.contains(key)) {
                if (aliases.putIfAbsent(key, value) != null) return original;
            } else {
                preserved.add(entry.trim());
                present.add(key);
            }
        }
        if (aliases.isEmpty()) return original;
        // Bedrock @r can target non-player entities; Java @r cannot.
        if (base == 'r' && present.contains("type")) return original;
        if (conflict(present, aliases, "distance", "r", "rm")
                || conflict(present, aliases, "level", "l", "lm")
                || conflict(present, aliases, "x_rotation", "rx", "rxm")
                || conflict(present, aliases, "y_rotation", "ry", "rym")
                || (aliases.containsKey("m") && present.contains("gamemode"))
                || (aliases.containsKey("c") && (present.contains("limit") || present.contains("sort")))) return original;

        String distance = range(aliases, "rm", "r", false);
        String level = range(aliases, "lm", "l", true);
        String pitch = rotation(aliases, "rxm", "rx", -90, 90);
        String yaw = rotation(aliases, "rym", "ry", -180, 180);
        if (aliases.containsKey("r") || aliases.containsKey("rm")) {
            if (distance == null) return original;
            preserved.add("distance=" + distance);
        }
        if (aliases.containsKey("l") || aliases.containsKey("lm")) {
            if (level == null) return original;
            preserved.add("level=" + level);
        }
        if (aliases.containsKey("rx") || aliases.containsKey("rxm")) {
            if (pitch == null) return original;
            preserved.add("x_rotation=" + pitch);
        }
        if (aliases.containsKey("ry") || aliases.containsKey("rym")) {
            if (yaw == null) return original;
            if (!yaw.isEmpty()) preserved.add("y_rotation=" + yaw);
        }
        if (aliases.containsKey("m")) {
            String mode = mode(aliases.get("m"));
            if (mode == null) return original;
            preserved.add("gamemode=" + mode);
        }
        if (aliases.containsKey("c")) {
            String count = aliases.get("c");
            if (!SIGNED.matcher(count).matches() || count.contains(".")) return original;
            long value;
            try { value = Long.parseLong(count); }
            catch (NumberFormatException e) { return original; }
            if (value == 0 || value == Long.MIN_VALUE || Math.abs(value) > Integer.MAX_VALUE) return original;
            if (base == 's' || (value < 0 && base == 'r')) return original;
            preserved.add("limit=" + Math.abs(value));
            // @r is random, other Bedrock base selectors choose nearest unless count is negative.
            if (base != 's') preserved.add("sort=" + (value < 0 ? "furthest" : base == 'r' ? "random" : "nearest"));
        }
        return preserved.isEmpty() ? "@" + base : "@" + base + "[" + String.join(",", preserved) + "]";
    }

    private static boolean conflict(Set<String> present, Map<String, String> aliases,
                                    String javaName, String minKey, String maxKey) {
        return present.contains(javaName) && (aliases.containsKey(minKey) || aliases.containsKey(maxKey));
    }

    private static String range(Map<String, String> aliases, String minimum, String maximum, boolean integer) {
        String min = aliases.get(minimum), max = aliases.get(maximum);
        if (min == null && max == null) return null;
        Pattern pattern = integer ? INTEGER : NON_NEGATIVE;
        if ((min != null && !pattern.matcher(min).matches()) || (max != null && !pattern.matcher(max).matches())) return null;
        if (min != null && max != null) {
            if (new BigDecimal(min).compareTo(new BigDecimal(max)) > 0) return null;
            if (new BigDecimal(min).compareTo(new BigDecimal(max)) == 0) return min;
        }
        return (min == null ? "" : min) + ".." + (max == null ? "" : max);
    }

    private static String rotation(Map<String, String> aliases, String minimum, String maximum, int lo, int hi) {
        String min = aliases.get(minimum), max = aliases.get(maximum);
        if (min == null && max == null) return null;
        if ((min != null && !validRotation(min, lo, hi)) || (max != null && !validRotation(max, lo, hi))) return null;
        if (lo == -180) {
            // Minecraft wraps +180 to -180. Java's "..180" is NOT the full yaw range.
            // Full Bedrock range must not be passed to Java as a bounded rotation range.
            if (max != null && new BigDecimal(max).compareTo(BigDecimal.valueOf(180)) == 0) {
                if (min == null || new BigDecimal(min).compareTo(BigDecimal.valueOf(-180)) == 0)
                    return ""; // full yaw range; omit redundant predicate
                return null; // cannot represent +180-inclusive partial range as one Java interval
            }
            if (min != null && new BigDecimal(min).compareTo(BigDecimal.valueOf(180)) == 0) return null;
            if (min != null && max == null && new BigDecimal(min).compareTo(BigDecimal.valueOf(-180)) == 0)
                return ""; // full yaw range
        }
        if (min != null && max != null) {
            int cmp = new BigDecimal(min).compareTo(new BigDecimal(max));
            if (cmp > 0) return null; // wrapped ranges cannot be represented by one Java range
            if (cmp == 0) return min;
        }
        return (min == null ? "" : min) + ".." + (max == null ? "" : max);
    }

    private static boolean validRotation(String value, int lo, int hi) {
        if (!SIGNED.matcher(value).matches()) return false;
        BigDecimal angle = new BigDecimal(value);
        return angle.compareTo(BigDecimal.valueOf(lo)) >= 0 && angle.compareTo(BigDecimal.valueOf(hi)) <= 0;
    }

    private static String mode(String mode) {
        boolean negated = mode.startsWith("!");
        String value = negated ? mode.substring(1) : mode;
        String canonical = switch (value.toLowerCase(Locale.ROOT)) {
            case "0", "s", "survival" -> "survival";
            case "1", "c", "creative" -> "creative";
            case "2", "a", "adventure" -> "adventure";
            case "3", "sp", "spectator" -> "spectator";
            default -> null;
        };
        return canonical == null ? null : (negated ? "!" : "") + canonical;
    }

    /** Split commas while retaining nested scores, NBT, quoted strings and escaped characters. */
    private static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        int start = 0, curly = 0, square = 0;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\\' && i + 1 < value.length()) { i++; continue; }
            if (quote != 0) { if (ch == quote) quote = 0; continue; }
            if (ch == '"' || ch == '\'') { quote = ch; continue; }
            if (ch == '{') curly++;
            else if (ch == '}') curly--;
            else if (ch == '[') square++;
            else if (ch == ']') square--;
            if (square < 0 || curly < 0) return null;
            if (ch == ',' && curly == 0 && square == 0) {
                out.add(value.substring(start, i));
                start = i + 1;
            }
        }
        if (quote != 0 || curly != 0 || square != 0) return null;
        out.add(value.substring(start));
        return out;
    }

    private static int topEquals(String value) {
        int curly = 0, square = 0;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\\' && i + 1 < value.length()) { i++; continue; }
            if (quote != 0) { if (ch == quote) quote = 0; continue; }
            if (ch == '"' || ch == '\'') { quote = ch; continue; }
            if (ch == '{') curly++;
            else if (ch == '}') curly--;
            else if (ch == '[') square++;
            else if (ch == ']') square--;
            if (ch == '=' && curly == 0 && square == 0) return i;
        }
        return -1;
    }
}
