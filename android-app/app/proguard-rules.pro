-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class ai.rootforge.os.** { kotlinx.serialization.KSerializer serializer(...); }
-keep class ai.rootforge.os.bridge.RootForgeBridgeService { *; }
-keep class ai.rootforge.os.shizuku.PrivilegedUserService { *; }
-keepclassmembers class ai.rootforge.os.shizuku.PrivilegedUserService {
    public <init>(...);
}
-keep class ai.rootforge.os.shizuku.IPrivilegedOps { *; }
-keep class ai.rootforge.os.shizuku.IPrivilegedOps$Stub { *; }
-keep class ai.rootforge.os.shizuku.IPrivilegedOps$Stub$Proxy { *; }
-keep class ai.rootforge.os.bridge.BridgeRequest { *; }
-keep class ai.rootforge.os.bridge.BridgeResponse { *; }
-keep class ai.rootforge.os.bridge.BridgeErrorBody { *; }
