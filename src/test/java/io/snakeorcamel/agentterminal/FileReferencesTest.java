package io.snakeorcamel.agentterminal;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FileReferencesTest {
    private final Path root = Path.of("/work/project");
    @Test void formatsWholeFileAndRanges() {
        assertEquals("@src/Example.java", format("src/Example.java", List.of()));
        assertEquals("@src/Example.java#L12", format("src/Example.java", List.of(new FileReferences.Lines(12,12))));
        assertEquals("@src/Example.java#L12-L34", format("src/Example.java", List.of(new FileReferences.Lines(12,34))));
    }
    @Test void sortsAndMergesOverlappingRangesWithoutExpandingAdjacentSelections() {
        assertEquals("@f#L2-L8 @f#L9 @f#L20", format("f", List.of(new FileReferences.Lines(20,20),
                new FileReferences.Lines(4,8), new FileReferences.Lines(2,5), new FileReferences.Lines(9,9))));
    }
    @Test void doesNotConfuseDirectoryPrefixesAndUsesAbsoluteForUnknownDirectory() {
        assertEquals("@/work/project-other/a", FileReferences.format(Path.of("/work/project-other/a"),root,List.of(),AgentProfile.Kind.Codex));
        assertEquals("@/work/project/a", FileReferences.format(root.resolve("a"),null,List.of(),AgentProfile.Kind.Claude));
    }
    @Test void keepsUnicodeAndQuotesWhitespaceInComposerSyntax() {
        for (var kind : AgentProfile.Kind.values()) assertEquals("@\"한글 경로/예제.java\"#L2", FileReferences.format(root.resolve("한글 경로/예제.java"), root,
                List.of(new FileReferences.Lines(2,2)),kind));
    }
    @Test void rejectsControlCharactersRatherThanSendingTerminalKeys() {
        assertThrows(IllegalArgumentException.class, () -> format("a\nb",List.of()));
        assertThrows(IllegalArgumentException.class, () -> format("a\"b",List.of()));
    }
    @Test void profilesAreCopiedAndRejectCommandsWithEnter() {
        var p = new AgentProfile("Codex",AgentProfile.Kind.Codex,"codex --model example");
        assertEquals(p, p.copy()); p.copy().command = "other"; assertEquals("codex --model example",p.command);
        p.command = "codex\nwhoami"; assertThrows(IllegalArgumentException.class,p::validate);
    }
    private String format(String name, List<FileReferences.Lines> ranges) {
        return FileReferences.format(root.resolve(name),root,ranges,AgentProfile.Kind.Codex);
    }
}
