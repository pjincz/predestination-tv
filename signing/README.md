# Release signing

Local files in this directory are ignored by Git, except this README.

- `release.jks`: private release signing key (JKS).
- `release.properties`: Gradle signing configuration, including passwords.
- Key alias: `predestination-tv`.

Back up both local files securely outside the repository. Future releases must use the same signing key.
Do not upload these files with APKs or include their contents in logs or issue reports.

Build a signed release from the repository root:

```powershell
.\gradlew.bat assembleRelease -PtargetAbi=armeabi-v7a
.\gradlew.bat assembleRelease -PtargetAbi=arm64-v8a

# x86 / x86_64
.\gradlew.bat assembleRelease -PtargetAbi=x86
.\gradlew.bat assembleRelease -PtargetAbi=x86_64
```

Both commands write `app/build/outputs/apk/release/app-release.apk`; copy each output before building the other architecture.
Release builds require `release.properties`; debug builds do not.
The release certificate differs from the debug certificate, so release APKs cannot update existing debug installations in place.
