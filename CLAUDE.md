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
  submodule copy here) and commit them there.
- Then bump the submodule pointers so each repo points at the other's new
  commit: this repo's `ccnix/` first, then ccnix's `mod/`. The pointers can't
  both be at each other's tip (that would be a hash cycle), so the last one
  bumped lags its sibling by one commit. That's expected. Each submodule has a
  `local` remote pointing at the sibling's folder, so fetch from that.
- If the sibling needs nothing, say so in a line, so it's clear it was checked.
- Pushing is never implied: ask before pushing either repo.
