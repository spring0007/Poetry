# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# sherpa-onnx 的 native 侧用 GetMethodID 按「名字 + 描述符」找合成回调上的
# invoke([F)Ljava/lang/Integer;，R8 看不到这条引用，混淆/内联掉就只能在真机上炸
# （JNI pending exception → SIGABRT，见 SherpaTts.ChunkCallback 的注释）。
# 现在 release 没开 minify，这条是给将来开的时候兜底的。
-keepclassmembers class com.example.poetry.media.SherpaTts$ChunkCallback {
    public java.lang.Integer invoke(float[]);
}