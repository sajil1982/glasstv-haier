# Contributing to glasstv

Thanks for helping out. glasstv is a fork of
[CleanYourTV](https://github.com/Gathin23/cleanyourtv); improvements that aren't
glasstv-specific are often worth sending upstream too.

- Open an issue describing the device (model, OS version) and what happened.
- For launcher changes, build with JDK 17 + Android SDK 34 (`cd launcher && ./gradlew assembleRelease`).
- Keep device-specific package lists in `examples/<device>/`, not in the launcher defaults.
- Don't commit build output, `local.properties`, APKs, or any personal data
  (IPs, Wi-Fi names, device backups).
