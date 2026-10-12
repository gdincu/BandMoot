# R8 / ProGuard rules for release builds.
# Android components referenced from AndroidManifest.xml are kept automatically.

# protobuf-javalite generated code (XiaomiProto): keep the message API used
# via parseFrom()/newBuilder(); everything else may be shrunk.
-keep class nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto* { *; }
-keep class com.google.protobuf.GeneratedMessageLite* { *; }
-dontwarn com.google.protobuf.**
