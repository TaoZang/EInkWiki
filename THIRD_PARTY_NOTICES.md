# Third-party notices

## Kiwix / libkiwix / libzim

This application links `org.kiwix:libkiwix:2.6.0`, which contains the Java bindings and native Kiwix/OpenZIM libraries. Those components are distributed under GNU GPL-compatible terms. Source and license information:

- <https://github.com/kiwix/java-libkiwix>
- <https://github.com/kiwix/libkiwix>
- <https://github.com/openzim/libzim>

The whole application is therefore distributed under GPL-3.0; see `LICENSE` and the bundled copy at [`licenses/GPL-3.0.txt`](licenses/GPL-3.0.txt).

The release tag matching an APK contains this application's Corresponding Source and build scripts. The Maven dependency coordinates above identify the binary used by the build.

## AndroidX Core

This application uses `androidx.core:core:1.15.0`, including `FileProvider`, to hand a verified update APK to Android's system package installer. AndroidX and its AndroidX transitive components are licensed under the Apache License 2.0. A complete local copy is included at [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt).

- <https://www.apache.org/licenses/LICENSE-2.0>
- <https://developer.android.com/jetpack/androidx/releases/core>

## ICU data

`app/src/main/assets/icu/icudt58l.dat` is the ICU 58 data file used by the official Kiwix Android application to support Unicode-aware search on Android. ICU is distributed under the Unicode/ICU License. The complete ICU 58.3 license and bundled data notices are included at [`licenses/ICU-58.3-LICENSE.txt`](licenses/ICU-58.3-LICENSE.txt).

- <https://github.com/unicode-org/icu/blob/main/LICENSE>
- <https://github.com/kiwix/kiwix-android>

## Noto Serif SC

The reader bundles `NotoSerifSC[wght].ttf` from the Google Fonts repository and exposes it only to locally rendered ZIM pages. The font is licensed under the SIL Open Font License 1.1. A complete local copy is included at [`licenses/NotoSerifSC-OFL.txt`](licenses/NotoSerifSC-OFL.txt).

- <https://github.com/google/fonts/tree/main/ofl/notoserifsc>
- Source commit: `2e61f4355afd22b801791b0df176065082423b87`

The local license files above are also packaged as readable APK assets.

## Other packaged libraries

The resolved release dependency graph also includes ReLinker 1.4.5, Kotlin standard library 1.9.20, JetBrains annotations, and AndroidX support components pulled by `libkiwix`/AndroidX Core. ReLinker, Kotlin, JetBrains annotations, and AndroidX components use permissive open-source licenses (Apache-2.0 or BSD-style terms); their exact versions are fixed by Gradle dependency resolution and can be inspected with:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

## Wikipedia content

ZIM content is not bundled into the APK. Users provide their own archives; each archive and its individual pages may contain different source and license notices.
