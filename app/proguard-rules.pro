# Bluetooth codec details are read reflectively from hidden framework classes.
-keepclassmembers class android.bluetooth.** { *; }
-dontwarn android.bluetooth.**

# NewPipe Extractor: runs YouTube's player JavaScript in Rhino and parses time-ago patterns by reflection.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.schabi.newpipe.extractor.services.youtube.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.**
-dontwarn javax.annotation.**

# Google Cast: the options provider is referenced only from the manifest.
-keep class androidx.media3.cast.DefaultCastOptionsProvider { *; }

# WorkManager (which Glance widgets render through) creates these by reflection; without their no-arg
# constructors every widget update fails and widgets stay on their loading placeholder.
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# Glance widget buttons (play/pause, next, previous, like) are created by class name when tapped.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { <init>(); }
