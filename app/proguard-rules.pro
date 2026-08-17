# libkiwix's Java classes are called through JNI; their names and native fields
# must not be changed by R8.
-keep class org.kiwix.libzim.** { *; }
-keep class org.kiwix.libkiwix.** { *; }
