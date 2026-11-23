# Dependencies and build inputs

SkyTube targets Minecraft Java Edition 1.21.11 and Java 21. Its Gradle build uses Mojang’s official class and method names for development. Loom remaps the release JAR to Fabric’s intermediary names.

| Component | Build pin | Role |
| --- | --- | --- |
| Minecraft Java Edition | 1.21.11 | Game client |
| Fabric Loader | 0.19.3 | Loads the client mod |
| Fabric API | 0.141.6+1.21.11 | Client lifecycle, rendering and input hooks |
| MCEF Modern | 0.3.3+mc1.21.11.jcef146.0.10 | Embedded Chromium browser and GPU texture |
| JCEF API | jcef-d3de827+cef-146.0.10+g8219561+chromium-146.0.7680.179 | Compile-time interface to MCEF’s bundled JCEF version |
| Fabric Loom | 1.15.5 | Gradle plugin for remapping |
| Gradle | 9.2.1 | Build and wrapper |
| JUnit Jupiter | 5.12.2 | Java tests |

Install Fabric Loader, Fabric API, and MCEF Modern separately. MCEF packages JCEF API and JCEFMaven inside its mod JAR. On first use MCEF downloads Chromium native files for the operating system. The release workflow distributes SkyTube and its own source JAR. SkyTube’s MIT license applies to its original code. MCEF Modern is licensed under LGPL-2.1-only and is resolved from the publisher’s Maven repository as an external dependency. The Gradle wrapper includes its Apache License 2.0.

- [Minecraft 1.21.11 Fabric porting notes](https://fabricmc.net/2025/12/05/12111.html)
- [Fabric API on Fabric Maven](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.141.6+1.21.11/)
- [Fabric Loom release on Fabric Maven](https://maven.fabricmc.net/net/fabricmc/fabric-loom/1.15.5/)
- [MCEF Modern 1.21.11 release, supplementary sources and license](https://modrinth.com/mod/mcef-modern/version/NfheQM6w)
- [MCEF Modern 1.21.11 source and test mod](https://github.com/DimasKama/mcef-modern/tree/1.21.11)
- [MCEF Modern published Maven artifacts](https://maven.dimaskama.net/releases/net/dimaskama/mcef-modern/0.3.3+mc1.21.11.jcef146.0.10/)
- [Gradle distribution and wrapper checksums](https://gradle.org/release-checksums/)

## Building a release

Use JDK 21 and Node.js 22. From the project root, run the following commands. On Windows use `gradlew.bat` to invoke Gradle.

```sh
node --test tests/web/*.test.js
./gradlew clean build
```

The installable JAR and the companion source JAR are in `build/libs/`. The single GitHub Actions workflow reads `mod_version` from `gradle.properties`. A new version pushed to `main` is tagged and released only after all required checks pass. The release contains both JARs and `SHA256SUMS.txt`; see the [release instructions](../README.md#build-and-verify).

To verify a downloaded release, put both JARs and the checksum file in the same directory and run `sha256sum --check SHA256SUMS.txt`. Windows PowerShell users can compare the output of `Get-FileHash -Algorithm SHA256 *.jar` to the two listed digests.

Native graphical tests additionally need a display, OpenGL 3.3 or later, and the platform libraries required by Chromium. See [testing](TESTING.md) for the graphical fixture, software-rendered cloud checks, and optional external playback check.
