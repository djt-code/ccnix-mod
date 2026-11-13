# The project page on Modrinth and CurseForge

The text for ccnix's pages on both sites, kept here so it changes with the
mod. Paste the summary and the description as they stand; set the rest by
hand (see "Settings" at the end). Both sites take Markdown. The summary is
one sentence that doesn't repeat the title (the sites' rule), under about
250 characters.

The point to get across: ComputerCraft's modems only pass messages between
computers. ccnix runs a real network stack over them (IPv4, ICMP, TCP, UDP,
DNS), and the mod's gateway joins those networks to real ones and the
internet.

## Summary

> A Unix-like system for CC: Tweaked computers, with its own TCP/IP stack and a gateway block that routes in-game networks onto real ones and the internet.

## Description

> **Needs CC: Tweaked 1.120 or later.** CC: Tweaked is no longer published on CurseForge (its last file there is 1.113.1, too old for this mod). Get it from [Modrinth](https://modrinth.com/mod/cc-tweaked) or [CC: Tweaked's site](https://tweaked.cc/).

ccnix turns every CC: Tweaked computer and turtle into a small Unix-like system, with nothing to install. ComputerCraft's modems on their own only pass messages between computers; ccnix runs a real network stack over them (IPv4, ICMP, TCP, UDP and DNS), and the mod's gateway block joins those networks to real ones and to the internet.

### In every computer's ROM

- **A shell, `ccsh`**, POSIX in its scripting and zsh-like at the prompt: pipes, redirection, job control (`&`, `fg`, `bg`, Ctrl+Z), history search and tab completion.
- **The everyday tools:** `ls`, `cat`, `grep`, `find`, `diff`, `sort`, `cut`, `tr`, `xargs`, `vi`/`vim`, `screen`, `ps`, `kill`, `df`, `du`, and more.
- **`init` and services** with a system log (`syslogd`, `logger`).
- **A real network stack** over CC's modems: IPv4, ARP, routing, TCP and UDP, DNS, and `ip`, `ping`, `traceroute`, `netstat`, `nc`, `tcpdump`, `telnet`/`telnetd`, `curl`/`httpd`, and an IRC client and server. A computer can be a `router` or a `fileserver`.
- **Turtle programs:** `mine` (strip mining with ore scans and crafting), `farm`, `mow`, and a building library (a map, routes, placing stairs and slabs the right way, blueprints, crafting).
- **`git`**, so a computer can keep itself up to date: `ccnix update`.
- **`claude`**, which lets Claude drive a turtle in plain language, with your own Anthropic API key. Claude is a trademark of Anthropic; ccnix is not made or endorsed by Anthropic.

### The Network Gateway

A block on CC cables that joins ComputerCraft's wired network to a real one:

- **NAT mode** (the default, and singleplayer's) runs inside the server: computers reach the internet as the server does, with nothing to set up, and port forwards let the outside reach them.
- **TUN mode** routes through a TUN device on a Linux server, so a real machine can `ping`, `telnet` or `nc` to a computer in the game. It needs a small helper the server's operator builds and installs (see the docs).

A Network Configurator item opens a gateway's live panel in game: its state, the frames and MACs it sees, and its VLANs, which it sets.

### What it does on the network, plainly

- **Computers** only reach what the server's CC: Tweaked HTTP rules and the gateway allow; the gateway does nothing until a block is placed and configured.
- **`ccnix update`**, run on a computer, fetches ccnix's files from GitHub (github.com/djt-code/ccnix).
- **`/ccnix rom update`**, a server operator's command (permission level 3), fetches the ROM from GitHub with `git` and asks before loading it.
- **In TUN mode** the mod starts the operator's own `ccnix-tun` helper, a separate program the operator compiles from the source in the repo. The jar contains no native code.
- Nothing is sent anywhere by the mod by itself: no telemetry, no update checks.

### Links

- Source, docs and issues: [ccnix-mod](https://github.com/djt-code/ccnix-mod) (the mod) and [ccnix](https://github.com/djt-code/ccnix) (the Lua: every program's manual is in its README and in game with `help`).
- License: BSD 3-Clause.

ccnix is not an official Minecraft product, and is not approved by or associated with Mojang or Microsoft.

## Settings

- **Name:** ccnix
- **Loader:** NeoForge; **Minecraft:** 1.21.1; **Java:** 21 (set by hand on CurseForge, where mc-publish can't).
- **Environment:** client and server both required (the gateway block and the configurator are registered on both).
- **Dependencies:** CC: Tweaked required; Advanced Peripherals optional (mc-publish sets these from the jar).
- **Categories:** Technology, Utility (CurseForge: Technology, API and Library if offered; Modrinth: technology, utility).
- **License:** BSD-3-Clause.
- **Source / issues links:** https://github.com/djt-code/ccnix-mod, https://github.com/djt-code/ccnix-mod/issues.
- **Icon:** 400×400, not a single flat colour (CurseForge's rule), no WebP.
- **Modrinth:** the description's "What it does on the network" covers its content rules on data sent to remote servers; say the same if its form asks.
