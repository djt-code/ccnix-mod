# ccnix-mod and ccnix are codependent

This repo (ccnix-mod, the NeoForge mod: the ROM and the TUN gateway) and
**ccnix** (the sibling folder `../ccnix`, the Lua: programs, libraries,
network stack, deploy) are one project in two repos, kept in lockstep. Each is
a submodule of the other:

- here, `ccnix/` is the ccnix repo. The ROM is built from that submodule's
  HEAD (`bin lib turtle pc help deploy`, via git archive);
- there, `mod/` is this repo (`update = none`, so recursive submodule commands end).

## The rule

Whatever is asked in either repo, look at the other one before deciding the
work is done. Check whether the goal needs changes there too, for example:
a frame or protocol change the Lua network stack also speaks, a ROM, boot or
update change the Lua side relies on, a rename, a setting, docs or help that
describe the other side. Read the sibling's code. Don't assume it's unaffected.

- If the sibling needs changes, make them in its own folder (not inside the
  submodule copy here), on a branch, in its own pull request.
- Submodule pointers only ever move to a commit on the sibling's `main`
  (normally a release tag), by pull request. A ccnix release brings this mod
  along (ccnix's ADR-0009 D4): this repo's `ccnix/` moves to it with a patch
  bump, then ccnix's `mod/` moves to this mod's release. The lockstep jobs do
  that by opening `bump/ccnix` here and `bump/mod` there, which merge on
  green; until those jobs exist, open the same two pull requests by hand.
  The pointers can't both be at each other's tip (a hash cycle), so the last
  one bumped lags its sibling by one commit. That's expected.
- A change that needs mod code too is two pull requests labelled `lockstep`:
  ccnix's first (it merges and releases), then this repo's with the code,
  `ccnix/` at that release's tag and `mod_version`.
- If the sibling needs nothing, say so in a line, so it's clear it was checked.

## Versions

Both use semantic versioning, each its own: the mod's is `mod_version` in
`gradle.properties`, ccnix's is its `deploy/version` (the ROM carries it;
`/ccnix` and ccnix's `ccnix version` show it). Bump them with the change
that calls for it: a new feature the minor, a fix the patch (while
they're 0.x, a breaking change is a minor bump too). A merged version bump
on a green `main` makes the release (with the jar, published on Modrinth and
CurseForge); nobody tags by hand.

## Pull requests (ADR-0009)

`main` is protected in both repos (a ruleset: a pull request, CI green,
merge commits only, no bypass). Never push to `main`.

- Branch from `main` (`feat/`, `fix/`, `docs/`, `ci/` + a topic), commit
  each change on it, push the branch, and open a pull request with `gh`.
  Fill in the template's checklist. In ccnix-mod add `--project ccnix`
  (only ccnix's items reach the board by themselves).
- Once its CI is green, merge it yourself: `gh pr merge <n> --merge
  --delete-branch` (the owner's standing decision, ADR-0009 D2). Then
  `git switch main && git pull`.
- Anything else that publishes (a tag or release by hand, a repo setting,
  a secret) is asked first.
- See CONTRIBUTING.md for the checks and the labels.
