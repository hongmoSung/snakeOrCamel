package io.snakeorcamel.agentterminal;

import org.junit.jupiter.api.Test;
import static io.snakeorcamel.agentterminal.ReadyDetector.State.*;
import static org.junit.jupiter.api.Assertions.*;

class ReadyDetectorTest {
    private final ReadyDetector detector = new ReadyDetector();
    private TerminalAccess.Screen screen(String text, String line, boolean running) {
        return new TerminalAccess.Screen(text,line,line,2,true,false,running,new Object());
    }
    @Test void recognizesTheCurrentClaudeAndCodexComposerWithoutSubmitting() {
        assertEquals(AgentProfile.Kind.Claude, detector.detect(screen("Claude Code\n❯ existing question\n? for shortcuts","❯ existing question",true),null).kind());
        assertEquals(AgentProfile.Kind.Codex, detector.detect(screen("OpenAI Codex\n› existing question\n100% context left","› existing question",true),null).kind());
    }
    @Test void oldBannerDoesNotMakeAnActiveShellOrAnOutputLineReady() {
        assertEquals(UNKNOWN, detector.detect(screen("Claude Code\n? for shortcuts\n❯ echo foo","❯ echo foo",false),null).state());
        assertEquals(UNKNOWN, detector.detect(screen("OpenAI Codex\n› previous prompt\nbuilding","building",true),null).state());
    }
    @Test void neverTypesIntoConfirmationOrBusyScreens() {
        for (String prompt : new String[]{"Do you trust this folder?", "Yes, proceed", "Sign in to continue", "Permission required"}) {
            assertEquals(CONFIRMATION,detector.detect(screen("Claude Code\n"+prompt+"\n❯", "❯",true),null).state());
        }
        assertEquals(UNKNOWN,detector.detect(screen("OpenAI Codex\nesc to interrupt\n›", "›",true),null).state());
    }
    @Test void customNeedsExplicitReadyTextAndAnExecutingCommand() {
        var profile = new AgentProfile("Custom",AgentProfile.Kind.Custom,"custom");
        var screen = screen("Agent\nAsk:","Ask:",true);
        assertEquals(UNKNOWN,detector.detect(screen,profile).state());
        profile.readyPattern="Ask:";
        assertEquals(READY,detector.detect(screen,profile).state());
        assertEquals(UNKNOWN,detector.detect(screen("Ask:","Ask:",false),profile).state());
        profile.readyPattern = "";
        assertEquals(UNKNOWN,detector.detect(screen("Claude Code\n❯ existing question","❯ existing question",true),profile).state());
    }
    @Test void noConnectionOrDistantPromptIsNeverReady() {
        assertEquals(STARTING, detector.detect(TerminalAccess.Screen.unknown(),null).state());
        assertEquals(UNKNOWN,detector.detect(new TerminalAccess.Screen("Claude Code\n❯", "❯", "❯", 20,true,false,true,new Object()),null).state());
    }
    @Test void recognizesObservedCodex0166FooterButNotItsLoadingScreen() {
        assertEquals(READY,detector.detect(screen("OpenAI Codex (v0.166.0)\n› Ask Codex to do anything\nGPT-6.1-Sol high Fast · /tmp", "› Ask Codex to do anything",true),null).state());
        assertEquals(STARTING,detector.detect(screen("OpenAI Codex (v0.166.0)\n loading\n› Ask Codex to do anything", "› Ask Codex to do anything",true),null).state());
    }
    @Test void recognizesCodexCompactComposerAfterTheStartupBannerScrollsAway() {
        for (String prompt : new String[]{"› Ask Codex to do anything", "› existing question"}) {
            String text = "Previous response\n" + prompt
                    + "\nGPT-6.1-Sol high fast · ~/OpenSpace/snakeOrCamel · 에이전트 터미널 계획 구현"
                    + "\n← for agents · ? for shortcuts\n1 warning · f2 to view";
            // The terminal cursor precedes the placeholder in an empty composer.
            var screen = new TerminalAccess.Screen(text,prompt,"› ",2,true,false,true,new Object());
            var result = detector.detect(screen,null);
            assertEquals(READY,result.state());
            assertEquals(AgentProfile.Kind.Codex,result.kind());
        }
    }
    @Test void compactCodexFooterStillRequiresAnActiveUnblockedComposer() {
        String footer = "\nGPT-6.1-Sol high fast · ~/project\n← for agents · ? for shortcuts";
        assertEquals(UNKNOWN,detector.detect(screen("› question" + footer,"› question",false),null).state());
        assertEquals(UNKNOWN,detector.detect(screen("working (1s)\n› question" + footer,"› question",true),null).state());
        assertEquals(CONFIRMATION,detector.detect(screen("Do you trust this folder?\n›" + footer,"›",true),null).state());
        assertEquals(UNKNOWN,detector.detect(screen("› earlier question\nbuilding" + footer,"building",true),null).state());
        assertEquals(UNKNOWN,detector.detect(screen("› question\nGPT-6.1-Sol high fast · ~/project\n? for shortcuts","› question",true),null).state());
        assertEquals(UNKNOWN,detector.detect(screen("› question\n← for agents · ? for shortcuts","› question",true),null).state());
    }
    @Test void claudeLoginStatusBlocksAnOtherwiseReadyComposer() {
        assertEquals(CONFIRMATION,detector.detect(screen("Claude Code v2.1.316\nNot logged in · Run /login\n❯ Try fix lint errors", "❯ Try fix lint errors",true),null).state());
    }
}
