package com.mirth.connect.client.ui.components;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;

/**
 * Compile-time stub of Mirth Connect's real
 * {@code com.mirth.connect.client.ui.components.MirthFieldConstraints}
 * (Administrator's mirth-client.jar / core-ui module) - the client-side
 * twin of the Manager app's {@code com.mirth.connect.manager.components.
 * MirthFieldConstraints}. Both ship inside their own application; the
 * MANAGER version is NOT on the Administrator classpath, this client
 * version IS.
 *
 * <p>Semantics mirrored from the real class (applied as a Document via
 * {@code field.setDocument(new MirthFieldConstraints(...))}):</p>
 * <ul>
 *   <li>{@link #MirthFieldConstraints(int)} - character limit only
 *       (0 = unlimited), any characters allowed</li>
 *   <li>{@link #MirthFieldConstraints(String)} - custom regex; the real
 *       class tests the regex with Matcher.find() over the WHOLE proposed
 *       content, so call sites should anchor patterns (^...$) for
 *       full-content checks</li>
 *   <li>{@link #MirthFieldConstraints(int, boolean, boolean, boolean)} -
 *       limit + uppercase conversion + letters-only / numbers-only
 *       presets (both false = any characters)</li>
 *   <li>Deletions are never restricted; insertions beyond the limit or
 *       failing the pattern are silently dropped</li>
 * </ul>
 *
 * <p>NEVER package this stub - the real class ships inside Mirth.</p>
 */
public class MirthFieldConstraints extends PlainDocument {

    private int limit;
    private final boolean toUppercase;
    private final Pattern pattern;

    /** Character limit only (0 = no limit), any characters. */
    public MirthFieldConstraints(int limit) {
        this(limit, false, null);
    }

    /** Custom regex constraint; use anchored patterns for full-content checks. */
    public MirthFieldConstraints(String pattern) {
        this(0, false, Pattern.compile(pattern));
    }

    /** Limit + uppercase conversion + letters-only / numbers-only presets. */
    public MirthFieldConstraints(int limit, boolean toUppercase, boolean lettersOnly, boolean numbersOnly) {
        this(limit, toUppercase, Pattern.compile(patternFor(lettersOnly, numbersOnly)));
    }

    private MirthFieldConstraints(int limit, boolean toUppercase, Pattern pattern) {
        super();
        this.limit = limit;
        this.toUppercase = toUppercase;
        this.pattern = pattern;
    }

    private static String patternFor(boolean lettersOnly, boolean numbersOnly) {
        if (lettersOnly && numbersOnly) {
            return "^[a-zA-Z_0-9\\-\\s]*$";   // alphanumeric (+ _ - space)
        } else if (lettersOnly) {
            return "^[a-zA-Z_\\-\\s]*$";      // letters (+ _ - space)
        } else if (numbersOnly) {
            return "^[0-9]*$";                // digits
        }
        return "^.*$";                        // match all
    }

    @Override
    public void insertString(int offset, String str, AttributeSet attr) throws BadLocationException {
        if (str == null) {
            return;
        }
        if (limit > 0 && getLength() + str.length() > limit) {
            return;
        }
        // (limit is mutable via setLimit, exactly like the real class)
        if (toUppercase) {
            str = str.toUpperCase();
        }
        if (pattern != null) {
            Matcher matcher = pattern.matcher(getText(0, getLength()) + str);
            if (!matcher.find()) {
                return;
            }
        }
        super.insertString(offset, str, attr);
    }

    /** Allow the limit to be changed after construction (real class API). */
    public void setLimit(int limit) {
        this.limit = limit;
    }
}
