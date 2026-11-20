## What and why



## Checklist

- [ ] `./gradlew build` passes (CI runs it, and compiles the TUN helper)
- [ ] `mod_version` bumped, if this ships (a feature the minor, a fix the patch)
- [ ] `ccnix/` only moved to a commit on ccnix's `main` (a release tag, normally)
- [ ] in-game checks: a `needs-in-game-test` issue a check (here for the mod's code), sub-issues of the change's issue, on the ccnix board (or none needed because ...)
- [ ] ccnix checked: changed in its own pull request first (both labelled `lockstep`), or it needs nothing because ...
- [ ] nothing private in the diff or the commit messages (hosts, addresses, usernames, paths, secrets)

Closes djt-code/ccnix#
