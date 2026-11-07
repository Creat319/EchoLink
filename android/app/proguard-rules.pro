# EchoLink release 规则。native 符号由 JNI 反射查找，保留 JNI 入口与统计模型。
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.echolink.app.audio.NativeAudioEngine { *; }
