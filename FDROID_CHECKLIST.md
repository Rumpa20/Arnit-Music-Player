# F-Droid release checklist

- [ ] Confirm the copyright holder and permission to license all project-owned code, images, fonts, and other assets.
- [x] Add the complete GPL-3.0 license text as `LICENSE` after copyright-holder confirmation.
- [ ] Add GPL-3.0 notices to project-owned source files and confirm all included artwork/assets are licensed for redistribution.
- [ ] Audit every Gradle dependency and bundled asset for license compatibility and include required notices.
- [ ] Review network use: LRCLIB online lyrics and any online playback endpoints.
- [ ] Verify permissions are minimal and explain each permission.
- [ ] Build a signed release APK/AAB as appropriate and test on supported Android versions.
- [ ] Publish source code in a public Git repository with a version tag matching the release.
- [ ] Confirm the F-Droid build can be reproduced from the tagged source using supported build tools.
- [ ] Prepare F-Droid metadata (summary, description, license, source code URL, issue tracker, categories, icon, screenshots).
- [ ] Submit to the F-Droid inclusion process and address reviewer feedback.

Do not submit until the unchecked legal, dependency, and reproducible-build items are resolved.
