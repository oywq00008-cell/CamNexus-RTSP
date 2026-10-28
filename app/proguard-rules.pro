# 保留 RootEncoder / RTSP-Server 的类：它们通过反射与 JNI 之外的动态方式访问内部类
-keep class com.pedro.** { *; }
-dontwarn com.pedro.**
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
