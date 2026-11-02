package io.snakeorcamel.agentterminal;

import java.nio.file.Path;
import java.util.*;

/** Pure reference formatting, independent of editor and terminal APIs. */
public final class FileReferences {
    private FileReferences() {}
    public record Lines(int first, int last) {
        public Lines {
            if (first < 1 || last < first) throw new IllegalArgumentException("Invalid line range");
        }
        public String suffix() { return "#L" + first + (first == last ? "" : "-L" + last); }
    }
    public static List<Lines> merge(List<Lines> selections) {
        List<Lines> sorted = selections.stream().sorted(Comparator.comparingInt(Lines::first).thenComparingInt(Lines::last)).toList();
        List<Lines> result = new ArrayList<>();
        for (Lines next : sorted) {
            if (!result.isEmpty() && next.first <= result.getLast().last) {
                Lines previous = result.removeLast();
                result.add(new Lines(previous.first, Math.max(previous.last, next.last)));
            } else result.add(next);
        }
        return List.copyOf(result);
    }
    public static String format(Path file, Path directory, List<Lines> selections, AgentProfile.Kind kind) {
        Path absolute = file.toAbsolutePath().normalize();
        Path base = directory == null ? null : directory.toAbsolutePath().normalize();
        String path = (base != null && absolute.startsWith(base) ? base.relativize(absolute) : absolute).toString().replace('\\', '/');
        if (AgentProfile.hasControl(path) || path.contains("\"") || path.contains("`")) {
            throw new IllegalArgumentException("This path cannot be represented safely. Copy the reference and check it in the agent.");
        }
        // Quoting is for the agent composer, never shell escaping or execution.
        String mention = "@" + (path.codePoints().anyMatch(Character::isWhitespace) ? "\"" + path + "\"" : path);
        List<Lines> merged = merge(selections);
        if (merged.isEmpty()) return mention;
        return String.join(" ", merged.stream().map(lines -> mention + lines.suffix()).toList());
    }
}
