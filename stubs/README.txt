Minimal compile-time stubs of the Mirth Connect API surface used by this
plugin (TransmissionModeProvider/Plugin/ClientProvider, FrameModeProperties,
StreamHandler, ObjectXMLSerializer, DataTypePropertyDescriptor, log4j Logger).

They let you SYNTAX-CHECK the sources without the real Mirth jars:

  javac -encoding UTF-8 -d /tmp/out \
      tools/compile-stubs/com/mirth/connect/**/*.java ... (all stubs) \
      shared/src/.../*.java server/src/.../*.java client/src/.../*.java

They are NOT functional and must never be packaged into the Mirth extension.
For a real build use mirth-libs per distribution/build.sh.
