package com.mirth.connect.client.ui;

import java.awt.Window;

import javax.swing.JDialog;

/**
 * Compile-time stub of Mirth Connect's real
 * {@code com.mirth.connect.client.ui.MirthDialog} (ships in the
 * Administrator's mirth-client.jar / core-ui module, Mirth 4.x).
 *
 * <p>Real class behavior (verified against the Mirth Connect source,
 * core-ui/src/com/mirth/connect/client/ui/MirthDialog.java):</p>
 * <ul>
 *   <li>abstract JDialog subclass with (Window), (Window, boolean) and
 *       (Window, String, boolean) constructors; the 3-arg form applies
 *       APPLICATION_MODALITY_TYPE when modal is true</li>
 *   <li>registers ESC on the root pane: onCloseAction() + dispose()</li>
 *   <li>setVisible/dispose toggle PlatformUI.MIRTH_FRAME.setCanSave(...)
 *       so the channel Save button is disabled while the dialog is open</li>
 * </ul>
 *
 * <p>NEVER package this stub - the real class ships inside Mirth.</p>
 */
public abstract class MirthDialog extends JDialog {

    public MirthDialog(Window owner) {
        this(owner, "", false);
    }

    public MirthDialog(Window owner, boolean modal) {
        this(owner, "", modal);
    }

    public MirthDialog(Window owner, String title, boolean modal) {
        super(owner, title, modal ? java.awt.Dialog.ModalityType.APPLICATION_MODAL
                                  : java.awt.Dialog.ModalityType.MODELESS);
    }

    /**
     * Real class calls this when ESC is pressed, before disposing.
     */
    public void onCloseAction() {}
}
