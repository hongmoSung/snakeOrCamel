package io.snakeorcamel.caseconvert;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/** Exercises the registered action against real editor documents and commands. */
public class CaseConvertActionTest extends BasePlatformTestCase {

    public void testConvertsSnakeCaseSelection() {
        configure("<selection><caret>my_variable_name</selection>");

        convert();

        assertText("myVariableName");
    }

    public void testConvertsKebabCaseSelection() {
        configure("<selection><caret>my-variable-name</selection>");

        convert();

        assertText("myVariableName");
    }

    public void testConvertsCamelCaseSelection() {
        configure("<selection><caret>myVariableName</selection>");

        convert();

        assertText("my_variable_name");
    }

    public void testConvertsOnlySelectedText() {
        configure("before <selection><caret>HTTPServer</selection> after");

        convert();

        assertText("before http_server after");
    }

    public void testConvertsMultipleSelectionsWithDifferentLengthChanges() {
        configure("<selection><caret>first_name</selection> + <selection><caret>secondValue</selection> + <selection><caret>third-name</selection>");
        assertEquals(3, myFixture.getEditor().getCaretModel().getCaretCount());

        convert();

        assertText("firstName + second_value + thirdName");
    }

    public void testSkipsBlankSelectionsAndUnselectedCarets() {
        configure("<selection><caret>first_name</selection> + <selection><caret>   </selection> + untouched<caret>");
        assertEquals(3, myFixture.getEditor().getCaretModel().getCaretCount());

        convert();

        assertText("firstName +     + untouched");
    }

    public void testLeavesDocumentUnchangedWithoutSelection() {
        configure("myVariableName<caret>");

        myFixture.testAction(action());

        assertText("myVariableName");
    }

    public void testEnablesActionWhenOnlySecondaryCaretHasSelection() {
        // The fixture treats the last caret marker as the primary caret.
        configure("<selection><caret>first_name</selection> + second<caret>");
        assertEquals(2, myFixture.getEditor().getCaretModel().getCaretCount());
        assertFalse(myFixture.getEditor().getCaretModel().getPrimaryCaret().hasSelection());

        Presentation presentation = myFixture.testAction(action());

        assertTrue(presentation.isEnabledAndVisible());
        assertText("firstName + second");
    }

    public void testDisablesAndHidesActionWithoutSelection() {
        configure("myVariableName<caret>");

        Presentation presentation = myFixture.testAction(action());

        assertFalse(presentation.isEnabled());
        assertFalse(presentation.isVisible());
    }

    public void testHandlesMissingEditor() {
        AnAction action = action();
        AnActionEvent event = TestActionEvent.createTestEvent(action, SimpleDataContext.EMPTY_CONTEXT);

        action.update(event);
        action.actionPerformed(event);

        assertFalse(event.getPresentation().isEnabled());
        assertFalse(event.getPresentation().isVisible());
    }

    public void testUndoesAndRedoesAllSelectionsAsOneCommand() {
        String original = "first_name + secondValue + third-name";
        String converted = "firstName + second_value + thirdName";
        configure("<selection><caret>first_name</selection> + <selection><caret>secondValue</selection> + <selection><caret>third-name</selection>");

        convert();
        assertText(converted);

        myFixture.performEditorAction(IdeActions.ACTION_UNDO);
        assertText(original);

        myFixture.performEditorAction(IdeActions.ACTION_REDO);
        assertText(converted);
    }

    private void configure(String text) {
        myFixture.configureByText(PlainTextFileType.INSTANCE, text);
    }

    private AnAction action() {
        AnAction action = ActionManager.getInstance().getAction("Name.Convert");
        assertInstanceOf(action, CaseConvertAction.class);
        return action;
    }

    private void convert() {
        Presentation presentation = myFixture.testAction(action());
        assertTrue("The action must be enabled for selected text", presentation.isEnabledAndVisible());
    }

    private void assertText(String expected) {
        assertEquals(expected, myFixture.getEditor().getDocument().getText());
    }
}
