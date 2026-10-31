package cn.ltalk.client.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationPanelTest {
    @Test void revealPreservesCredentialsAndResetsOnFormChangeOrClear() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AuthenticationPanel panel = new AuthenticationPanel("alice"); panel.state(true, false);
            assertTrue(panel.password.echoCharIsSet()); panel.password.setText(" 密码!123 ");
            panel.password.setCaretPosition(3); toggle(panel, "showPassword").doClick();
            assertFalse(panel.password.echoCharIsSet()); assertArrayEquals(" 密码!123 ".toCharArray(), panel.password.getPassword());
            assertEquals(3, panel.password.getCaretPosition()); toggle(panel, "showPassword").doClick(); assertTrue(panel.password.echoCharIsSet());
            toggle(panel, "showPassword").doClick(); panel.setRegistering(true); assertTrue(panel.password.echoCharIsSet());
            panel.password.setText("pass!12"); panel.confirmation.setText("pass!12");
            toggle(panel, "showPasswordConfirmation").doClick(); assertFalse(panel.confirmation.echoCharIsSet()); assertTrue(panel.password.echoCharIsSet());
            panel.state(true, true); assertFalse(toggle(panel, "showPassword").isEnabled()); assertFalse(toggle(panel, "showPasswordConfirmation").isEnabled());
            panel.clearCredentials(false); assertTrue(panel.confirmation.echoCharIsSet()); assertTrue(panel.password.echoCharIsSet());
            assertEquals(0, panel.password.getPassword().length); assertEquals(0, panel.confirmation.getPassword().length);
            assertEquals("alice", panel.user.getText());
        });
    }
    private static JToggleButton toggle(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return (JToggleButton) child;
            if (child instanceof Container nested) { JToggleButton found = toggle(nested, name); if (found != null) return found; }
        }
        return null;
    }
}
