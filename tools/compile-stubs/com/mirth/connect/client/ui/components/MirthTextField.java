package com.mirth.connect.client.ui.components;

import javax.swing.JTextField;

/**
 * Compile-time stub of Mirth Connect's real
 * {@code com.mirth.connect.client.ui.components.MirthTextField}
 * (Administrator's mirth-client.jar / core-ui module).
 *
 * <p>Real class adds, over plain JTextField: right-click Cut/Copy/Paste/
 * Delete/Select-All popup, Ctrl+S triggering the context-sensitive save,
 * save-button enabling on edits, and a TRIMMING getText(). Signatures here
 * mirror the real class; only the trimming getText is implemented.</p>
 *
 * <p>NEVER package this stub - the real class ships inside Mirth.</p>
 */
public class MirthTextField extends JTextField {

    public MirthTextField() {
        super();
    }

    /** Real class trims - callers see the same behavior at runtime. */
    @Override
    public String getText() {
        return super.getText().trim();
    }
}
