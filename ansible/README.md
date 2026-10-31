# ansible: the dev server

`site.yml` sets up the dma.space pack's server with ccnix on Ubuntu 26.04,
as [../doc/ubuntu-server.md](../doc/ubuntu-server.md) does by hand:
Java 21, NeoForge, the pack's mods from a bundle, the gateway's TUN helper
and device, systemd units, the gateway's config. Running it again changes
nothing unless the bundle or a variable did.

| File | What |
|---|---|
| `inventory.example.ini` | the hosts: copy to `inventory.ini` (ignored by git) with yours; the user needs passwordless sudo |
| `group_vars/minecraft.yml` | versions, heap, ports, `server.properties` keys, the gateway's mode, address, CC networks and routes, the TUN device, forwarding and NAT, NAT mode's policy and forwards |
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
- **The gateway's mode**, `ccnix_gateway_mode`: `tun` (this playbook's
  default) or `nat` (the mod's own default: no host setup). The mode is
  the admin's, set here and nowhere in game.
- **`ccnix-tun.service`** (root, oneshot, TUN mode) makes the device at
  boot with `ccnix-tun-setup`, before the server: its address, routes into
  it (`ccnix_cc_networks`, in table `ccnix_tun_table`, after the host's own
  routes), forwarding (`ccnix_forward`), NAT (`ccnix_nat`). In NAT mode the
  unit is stopped and disabled, and `ccnix-tun-setup --remove` takes the
  device, table and rules down.
- **`minecraft.service`** runs `run.sh nogui` in a tmux session, and stops
  it with `stop` at the console. `mc-console` attaches to it (Ctrl+B D
  leaves); `mc-cmd "op <you>"` types a command.
- **A new bundle** stops the server, replaces `mods/` and unpacks the
  pack's `config/` and `defaultconfigs/` over the old, then starts it.
  `config/ccnix-server.toml`'s keys are set from the variables every run
  (the mod keeps its own comments in the file), the routes only with
  `ccnix_ansible_routes` and the forwards only with
  `ccnix_ansible_forwards`.

**The gateway's routes** belong to the play or to the ops, as two
variables say:

| `ccnix_op_routes` | `ccnix_ansible_routes` | |
|---|---|---|
| `true` | `false` | ops change them in game (`/ccnix route add\|del`); the play writes `ccnix_gateway_routes` into a new config only (the default) |
| `true` | `true` | ops change them, and each run puts `ccnix_gateway_routes` back |
| `false` | `true` | the play's alone |

Ops' routes are no larger than `ccnix_op_route_max_subnet` (16: a /16)
and inside `ccnix_cc_networks`; they never reach the host's routing.

**NAT mode's settings** (`[nat]`), all the admin's:

| Variable | Default | |
|---|---|---|
| `ccnix_nat_deny`, `ccnix_nat_allow` | private, loopback, link-local, CGNAT, multicast, reserved, port 25; none | where CC computers may not connect, and exceptions |
| `ccnix_nat_max_per_computer`, `ccnix_nat_max_total` | 16, 256 | open connections and flows |
| `ccnix_nat_tcp_idle`, `ccnix_nat_udp_idle` | 600, 60 | seconds before a quiet one closes |
| `ccnix_nat_dns_relay` | `system` | port 53 at the gateway: the host's resolver, an address, or `""` |
| `ccnix_nat_forwards` | `[]` | `"25570/tcp -> 10.1.1.10:23"` |
| `ccnix_nat_op_forwards`, `ccnix_nat_op_forward_ports` | `false`, `""` | ops may change forwards in game, on these ports only |
| `ccnix_ansible_forwards` | `false` | each run sets the forwards to `ccnix_nat_forwards` |

The limits and lists apply only to CC computers' connections through a
Network Gateway, never to the server's own or its players'. Forwards are
NAT mode's: in TUN mode, forward ports with the host's firewall.

`server.properties` keys not in `server_properties` are the server's own:
edit them on the host, and the play leaves them alone.
