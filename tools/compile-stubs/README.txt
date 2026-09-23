Minimal compile-time stubs of the Mirth Connect API surface used by this
plugin (TransmissionModeProvider/Plugin/ClientProvider, FrameModeProperties,
StreamHandler, ObjectXMLSerializer, DataTypePropertyDescriptor, log4j Logger)
PLUS the Administrator client-UI kit the settings dialog links against
(com.mirth.connect.client.ui.MirthDialog, UIConstants, and
com.mirth.connect.client.ui.components.MirthTextField / MirthCheckBox /
MirthComboBox / MirthFieldConstraints). All UI stubs mirror the REAL Mirth
4.5.x signatures (verified against the Mirth Connect source, core-ui module):
- MirthDialog: abstract, ctors (Window) / (Window, boolean) /
  (Window, String, boolean); ESC close + Save-button interlock at runtime.
- MirthComboBox: ONLY a no-arg constructor - prefill with addItem(...).
- MirthFieldConstraints: PlainDocument applied via setDocument(...);
  (int limit) / (String pattern) / (int, toUppercase, lettersOnly,
  numbersOnly) + setLimit(int); regex is tested with Matcher.find() over
  the whole proposed content, so use anchored patterns.

They let you SYNTAX-CHECK the sources without the real Mirth jars:

  javac -encoding UTF-8 -d /tmp/out \
      tools/compile-stubs/com/mirth/connect/**/*.java ... (all stubs) \
      shared/src/.../*.java server/src/.../*.java client/src/.../*.java

They are NOT functional and must never be packaged into the Mirth extension.
For a real build use mirth-libs per distribution/build.sh.
