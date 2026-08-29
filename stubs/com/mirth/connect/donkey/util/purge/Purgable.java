package com.mirth.connect.donkey.util.purge;

import java.util.Map;

/**
 * <p><b>STUB - DO NOT DEPLOY.</b> This is a compile-time fallback for the
 * real {@code com.mirth.connect.donkey.util.purge.Purgable} interface
 * that ships inside Mirth Connect's {@code donkey-server.jar}.</p>
 *
 * <p>This stub exists so the project compiles on environments where only
 * a subset of the Mirth Connect jars is available. The proper fix is to
 * add {@code donkey-server.jar} to your compile classpath - see the
 * project README ("IntelliJ IDEA Setup" section) for details. If you
 * use this stub, you do so at your own risk - the interface signature
 * is verified to match Mirth Connect 4.5+, but earlier or later Mirth
 * versions may diverge.</p>
 *
 * <p><b>Usage:</b>
 * <ol>
 *   <li>Only use this stub if your build fails with
 *       {@code "cannot access com.mirth.connect.donkey.util.purge.Purgable"}.</li>
 *   <li>Add the {@code stubs/} directory to your IDE/Maven compile
 *       source roots.</li>
 *   <li>Make sure this stub is NOT packaged into the final jar - it
 *       would clash with the real Purgable interface at runtime.</li>
 *   <li>Prefer the real {@code donkey-server.jar} over this stub.</li>
 * </ol></p>
 *
 * <p>The real interface (Mirth Connect 4.5+) is:</p>
 * <pre>
 * package com.mirth.connect.donkey.util.purge;
 *
 * import java.util.Map;
 *
 * public interface Purgable {
 *     String getPluginPointName();
 *     Map&lt;String, Object&gt; getPurgedProperties();
 * }
 * </pre>
 */
public interface Purgable {
    String getPluginPointName();
    Map<String, Object> getPurgedProperties();
}
