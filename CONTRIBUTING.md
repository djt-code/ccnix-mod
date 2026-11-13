# Contributing to ccnix-mod

ccnix-mod (this repo: the NeoForge mod, the ROM and the TUN gateway) and
[ccnix](https://github.com/djt-code/ccnix) (the Lua) are one project in
two repos. Each is a submodule of the other. The flow, versions, releases
and the lockstep between them are decided in ccnix's
[ADR-0009](https://github.com/djt-code/ccnix/blob/main/doc/adr/0009-ci-releases-and-publishing.md)
and described in its
[CONTRIBUTING.md](https://github.com/djt-code/ccnix/blob/main/CONTRIBUTING.md);
what differs here is below.

## The flow

The same as ccnix's: `main` is protected, every change is a pull request
from a `feat/`, `fix/`, `docs/` or `ci/` branch, CI (`build`) must pass,
and it merges with a merge commit (never squash or rebase: ccnix's `mod/`
names this repo's commits by hash).

## The checks

```sh
git submodule update --init ccnix    # the Lua the ROM is built from
./gradlew build                      # the ROM, the mod and its tests (Java 21)
cc -O2 -Wall -Werror -o /tmp/ccnix-tun helper/ccnix-tun.c   # the TUN helper (Linux)
```

## Versions and releases

`mod_version` in `gradle.properties` is the mod's version (semantic
versioning, its own, separate from ccnix's). Bump it in the pull request
that ships something. When that merges and `main` is green, a release
`vX.Y.Z` is made with the jar, and the jar is published on Modrinth and
CurseForge. Nobody tags by hand.

## The ROM and ccnix

The ROM is built from `ccnix/`'s HEAD. `ccnix/` only ever points at a
commit on ccnix's `main`, normally a ccnix release:

- **a ccnix release** opens a pull request here that moves `ccnix/` to it
  and bumps the patch version; it merges on green;
- **a change with mod code that needs new Lua**: ccnix's pull request
  merges and releases first; then this repo's pull request, labelled
  `lockstep`, carries the code, `ccnix/` at that release's tag and the
  version.

## Tracking and safety

Issues are on the [ccnix board](https://github.com/users/djt-code/projects/1),
with the same labels as ccnix. These repos are public: no hostnames,
addresses, usernames, home paths or secrets in files or commit messages
(`ansible/inventory.ini` is ignored for that reason; copy
`inventory.example.ini`).
