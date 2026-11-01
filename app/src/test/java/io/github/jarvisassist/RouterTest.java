package io.github.jarvisassist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class RouterTest {

    private static String auto(String text, boolean phone, boolean pc) {
        return Router.decideAuto(text, phone, pc).engine;
    }

    @Test
    public void simpleQuestionsStayOnThePhone() {
        assertEquals("local", auto("In two sentences, what is photosynthesis", true, true));
        assertEquals("local", auto("What is seventeen times twenty three", true, true));
        assertEquals("local", auto("Summarise the French Revolution in one sentence", true, true));
        assertEquals("local", auto("Set a timer for ten minutes", true, true));
    }

    @Test
    public void exactFormatRequestsGoToClaude() {
        assertEquals("claude", auto("Reply with exactly three words about the sea", true, true));
        assertEquals("claude", auto("Describe it in 20 words", true, true));
        assertEquals("claude", auto("Give me at least five reasons", true, true));
        assertEquals("claude", auto("Write it in twenty-five words", true, true));
    }

    @Test
    public void codeHighStakesAndReasoningGoToClaude() {
        assertEquals("claude", auto("Write a Python function to sort a list", true, true));
        assertEquals("claude", auto("What dose of ibuprofen is safe", true, true));
        assertEquals("claude", auto("Compare the pros and cons of two plans", true, true));
        assertEquals("claude", auto("Explain it step by step", true, true));
    }

    @Test
    public void longInputGoesToClaude() {
        StringBuilder sb = new StringBuilder("Summarise this: ");
        for (int i = 0; i < 120; i++) {
            sb.append("word ");
        }
        assertEquals("claude", auto(sb.toString(), true, true));
    }

    @Test
    public void askingForClaudeByNameWins() {
        assertEquals("claude", auto("Ask Claude what the capital of France is", true, true));
        assertEquals("claude", auto("Claude, what time is sunrise", true, true));
        assertEquals("claude", auto("what is two plus two with claude", true, true));
    }

    @Test
    public void askingForThePhoneWins() {
        assertEquals("local", auto("Use Nano to explain the greenhouse effect", true, true));
        assertEquals("local", auto("answer this offline: write a python function", true, true));
    }

    @Test
    public void whenOneEngineIsMissingTheOtherAnswers() {
        assertEquals("claude", auto("What is photosynthesis", false, true));
        assertEquals("local", auto("What is photosynthesis", true, false));
        assertEquals("local", auto("Write a python function", true, false));
        assertEquals("local", auto("Ask Claude about photosynthesis", true, false));
        assertEquals("claude", auto("answer this offline", false, true));
    }

    @Test
    public void everySelectionHasAReason() {
        assertEquals("short, simple question", Router.decideAuto("hello there", true, true).reason);
        assertEquals("exact format wanted", Router.decideAuto("exactly two words", true, true).reason);
    }

    @Test
    public void explicitSwitchChoices() {
        assertEquals("claude", Router.pick("claude"));
        assertEquals("local", Router.pick("local"));
        assertNull(Router.pick("cloud"));
        assertNull(Router.pick("auto")); // auto is decided per question by decideAuto
    }
}
