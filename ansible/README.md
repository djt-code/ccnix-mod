# ansible: the dev server

`site.yml` sets up the dma.space pack's server with ccnix on Ubuntu 26.04,
as [../doc/ubuntu-server.md](../doc/ubuntu-server.md) does by hand:
Java 21, NeoForge, the pack's mods from a bundle, the gateway's TUN helper
and device, systemd units, the gateway's config. Running it again changes
nothing unless the bundle or a variable did.

| File | What |
|---|---|
| `inventory.ini` | the hosts (`devserver`, 192.0.2.10, as `admin` with passwordless sudo) |
| `group_vars/minecraft.yml` | versions, heap, ports, `server.properties` keys, the gateway's address, routes, TUN networks, forwarding and NAT |
| `site.yml` | the play |
| `templates/` | `minecraft.service`, `ccnix-tun.service`, the first `ccnix-server.toml` |
| `run-on-host.sh` | runs the play on the host itself, for a controller without Ansible |

## Running it

Build the mod and the bundle first, on the desktop:

```sh
./gradlew build
helper/server-bundle.sh "<your CurseForge instance folder>"   # server-bundle.tar.gz
```

Then, from Linux, macOS or WSL with Ansible:

```sh
cd ansible && ansible-playbook site.yml
```

or from Git Bash on Windows, which copies `ansible/`, the helper and the
bundle (when it changed) to `~/ccnix-ansible` on the host, installs
`ansible-core` there and runs the play against the host itself:

```sh
ansible/run-on-host.sh                     # [host] [bundle] [ansible-playbook options]
ansible/run-on-host.sh devserver server-bundle.tar.gz --check --diff
```

## What it makes

- **`minecraft`**, a system account (home `/srv/minecraft`, no login
  shell), runs the server in `/srv/minecraft/server`, and the gateway's
  helper `ccnix-tun` as the server's child. No separate ccnix account: the
  helper needs no privileges, because the TUN device belongs to
  `minecraft`.
- **`ccnix-tun.service`** (root, oneshot) makes the device at boot with
  `ccnix-tun-setup`, before the server: its address, routes into it,
  forwarding (`ccnix_forward`), NAT (`ccnix_nat`).
- **`minecraft.service`** runs `run.sh nogui` in a tmux session, and stops
  it with `stop` at the console. `mc-console` attaches to it (Ctrl+B D
  leaves); `mc-cmd "op <you>"` types a command.
- **A new bundle** stops the server, replaces `mods/` and unpacks the
  pack's `config/` and `defaultconfigs/` over the old, then starts it.
  `config/ccnix-server.toml`'s keys are set from the variables every run
  (the mod keeps its own comments in the file).

`server.properties` keys not in `server_properties` are the server's own:
edit them on the host, and the play leaves them alone.
