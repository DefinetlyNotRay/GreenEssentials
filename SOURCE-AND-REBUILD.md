# Source and rebuilding

GreenEssentials source: https://github.com/DefinetlyNotRay/GreenEssentials

The repository includes the source, Gradle wrapper, resource files, and build scripts. Match a distributed version to the commit whose build.gradle declares that version. Retain these sources and notices when redistributing modified versions.

## Build

Install JDK 25. From the repository root on Windows:

```powershell
.\gradlew.bat build '-Pminecraft_version=26.1' --console=plain
.\gradlew.bat build '-Pminecraft_version=26.1.2' --console=plain
.\gradlew.bat build '-Pminecraft_version=26.2' --console=plain
.\gradlew.bat build '-Pminecraft_version=26.3' --console=plain
```

On Linux/macOS use `./gradlew` with the same arguments. Installable shaded JARs appear in build/libs. Unit tests run as part of build. The nodeps JAR is only an intermediate artifact.

## Bundled dependency source

- MoulConfig 4.7.2 source is preserved at third_party/moulconfig/MoulConfig-4.7.2-source.zip, from upstream tag 4.7.2 (commit bd7b9aa3dbf7cef428280daf0156cb5e06338530). Upstream: https://github.com/NotEnoughUpdates/MoulConfig/tree/4.7.2
- MoulConfig 4.8.0 source (used by the 26.3 build) is preserved at third_party/moulconfig/MoulConfig-4.8.0-source.zip, from upstream tag 4.8.0 (commit 4eaeb73cdaebb083d1d56d310fe1851ded1b7828). Upstream: https://github.com/NotEnoughUpdates/MoulConfig/tree/4.8.0
- LibNinePatch 1.2.0 source is preserved at third_party/libninepatch/libninepatch-1.2.0-sources.jar, from Maven Central. Upstream: https://github.com/Juuxel/LibNinePatch
- MoulConfig is bundled with its Java package relocated from io.github.notenoughupdates.moulconfig to dev.greenhouse.deps.moulconfig. The relocation and resource/service processing are specified in build.gradle. Its access widener is copied into src/main/resources/greenessentials.accesswidener for 26.1–26.2 and src/compat/26.3/resources/greenessentials.accesswidener for 26.3.

## Rebuild against modified MoulConfig

Extract the matching source archive, modify it, and use its Gradle build to publish the matching module to your local Maven repository (publishToMavenLocal): modern-26.1:4.7.2 for 26.1/26.1.2, modern-26.2:4.7.2 for 26.2, or modern-26.3:4.8.0 for 26.3. GreenEssentials checks mavenLocal before remote repositories. Rebuild with `-Pmoulconfig_version=YOUR_VERSION` if you changed the version; the group remains org.notenoughupdates.moulconfig and the module follows the target above. Use JDK 25 and the matching Minecraft target as above. Update the access widener if your library changes require it. The same shadow task relocates and packages your modified library.

The code and build scripts are provided so users can modify and recombine the application with the library. Copyright and third-party notices are retained; see LICENSE, MOULCONFIG-LGPL-3.0.txt, MOULCONFIG-GPL-3.0.txt, LIBNINEPATCH-LICENSE, and THIRD-PARTY-NOTICES.md.
