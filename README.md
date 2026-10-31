# ccnix

A Minecraft mod (NeoForge 1.21.1, with CC: Tweaked) with two parts:

- **The ROM.** [ccnix](https://github.com/djt-code/ccnix)'s programs go into every computer's ROM: the
  shell `ccsh`, the POSIX utilities, `git`, the network stack, `init` and the services, telnet, and the turtle
  programs. They're on the path at every boot, with nothing to install. Each computer can pull updates over its ROM
  with `ccnix update`, and the server's ops update the ROM itself with `/ccnix rom update`.
- **The Network Gateway.** A block on CC cables that joins ComputerCraft's wired network to a real one: in NAT mode
  (the default) from inside the server process, with nothing to set up, so CC computers reach the internet as the
  server does and the outside reaches them through port forwards; or in TUN mode through a TUN device on a Linux
  server, which routes, so a real machine can `telnet` to a CC computer, `ping` it, or `nc` to it.

## The ROM

CC: Tweaked builds every computer's ROM from the server's data packs. The mod adds one, which holds the repo's
`bin/`, `lib/`, `turtle/`, `pc/`, `help/` and `deploy/` at `/rom/ccnix`, and `/rom/autorun/ccnix.lua`.

At each boot that autorun runs `deploy/boot.lua`. It puts the programs on the path and their help on the help path,
and makes `ls` ccnix's. It brings the network up while `net.boot` is on. With `init.boot`, `ccsh.login` or
`ccnix.autostart` set, it runs those at the end of startup. The ccnix repo's README has the details.

### On a computer: updates without the admin

`ccnix update` pulls the latest from the repo into the computer's `/ccnix`, with ccnix's `git`. It keeps only
the files that differ from the ROM's: a file the ROM already has, with the same git blob, isn't fetched. From the
next boot, each program, library and help page comes from `/ccnix` if it's there, and from the ROM if not.

- `ccnix` says what the ROM is and which updates are in use.
- `ccnix reset` drops the updates.
- **When the ROM changes,** updates made against the old ROM are left out until the next `ccnix update`.
- **When the ROM is newer than the branch,** an update keeps nothing.

The ROM's `.manifest` is what makes this work. It holds the commit, its time, the repo and branch to pull from, and
every file's blob SHA-1.

### On the server: updates with git

The ROM comes from one of two places:
- **The jar.** It's the ccnix repo as committed at the `ccnix/` submodule's commit when the mod was built.
- **A checkout.** If the server's folder has `ccnix-rom/`, a git checkout of the ccnix repo, the ROM comes from there
  instead.

`/ccnix rom update` (permission level 3, in game or at the server console) does it all while the server runs: it
clones the repo the ROM came from into `ccnix-rom/` the first time (its branch, shallow), `git pull --ff-only`s it
after that, and reloads the data packs, which rebuilds the ROM. It needs `git` on the server. By hand, the same is:

```sh
cd /path/to/server
git clone https://github.com/djt-code/ccnix ccnix-rom     # once
git -C ccnix-rom pull                                    # each update
```

then `/reload`. Computers see the new files at once; boot changes apply at their next boot.

- **What's served:** the files in the checkout's folders, as they are on disk, so a local edit is served too. The
  commit and its time come from `git` on the server.
- **The repo computers pull from:** the checkout's `origin`, if it's an https URL (or `git@github.com:`), and
  otherwise the jar's.
- **Switching back:** delete `ccnix-rom/` and `/reload` to go back to the jar's ROM.
- **Status:** `/ccnix` (operators) shows which ROM is in use, its commit and where computers pull from. The server
  log says so at each load.

A data pack can't hold a file whose name has capitals or `[`, so the ROM leaves out `bin/[.lua`. `ccnix update`
fetches it into `/ccnix`, as it does any file the ROM lacks.

## The Network Gateway

The gateway joins CC's wired network to the real one, in one of two modes, the server admin's choice
(`gateway.mode` in the config; [ADR-0002](https://github.com/djt-code/ccnix/blob/main/doc/adr/0002-nat-mode-gateway.md)):

| | **NAT** (the default) | **TUN** |
|---|---|---|
| Runs | in the server process: no privileges, helper or setup; any OS, a container, singleplayer | through a TUN device on a Linux server, set up as root |
| CC to the outside | TCP and UDP, as the server's own connections | routed, with real addresses (`FORWARD`, `NAT` on the host) |
| ping, traceroute past the gateway | no: the gateway says "net unreachable" | yes |
| The outside to CC | only through port forwards (`nat.forwards`, ops' if allowed) | routed, as the host's routing and firewall allow |
| Protection | a deny list and limits for CC computers' connections, on by default | the host's firewall |
| DNS at the gateway's address | relayed to the server's resolver (on Windows, answered from it) | whatever the host serves there |

Either way the gateway is transport only: it carries IP, TCP and UDP and never reads what's inside (the Windows DNS
answer is the one exception). Programs on the computers do everything above that.

### NAT mode

Nothing to set up: place a gateway and route at it. CC computers' connections to the outside become the server's
own (Java sockets), carried byte for byte, and the gateway answers ping at its address. What's in `[nat]`:

- **Protection** (applies only to CC computers' connections through a gateway, never to the server's own or its
  players'): `deny` blocks private, loopback, link-local (the cloud metadata service), carrier-grade NAT,
  multicast and reserved addresses, and port 25 anywhere; `allow` makes exceptions; `maxPerComputer` (16) and
  `maxTotal` (256) cap open connections and flows; `tcpIdle` (600 s) and `udpIdle` (60 s) close quiet ones. A
  refused TCP connection gets a reset, a refused datagram "port unreachable".
- **DNS:** `dnsRelay = "system"` relays port 53 at the gateway's address, unread, to the first `nameserver` in
  `/etc/resolv.conf`, so `ip dns 10.0.0.1` works on a host that blocks outbound DNS. On Windows, which has no such
  file, the gateway answers A and PTR questions from the Windows resolver instead. An address relays there; `""`
  turns it off. DNS sent to any other address is never touched.
- **Port forwards:** `forwards = ["25570/tcp -> 10.1.1.10:23"]` listens on the server's port and connects to the CC
  computer, which sees the client's real address (a client on the server's own loopback shows as the gateway's). UDP
  too. With `opForwards`, ops change them in game, on the ports in `opForwardPorts` only (`"25570-25579"`: the
  ones the host gave the server):

  ```
  /ccnix forward                                 the forwards, and the ports ops may use
  /ccnix forward add 25571 10.1.1.10:23          (or 25571/udp)
  /ccnix forward del 25571
  ```

### TUN mode

```
 CC host 10.0.0.5 ─┐
                   ├─ cable ─ [Network Gateway] ··· ccnix-tun ─ ccnix0 (10.0.0.1) ─ the server's kernel ─ eth0
 CC router 10.0.0.2┘   10.0.0.1, MAC "gateway",     (helper,
   └ 10.1.0.0/16 behind it   channel 4040         no privileges)
```

#### How it works

The **gateway** is the server's link onto the CC network, and the **server** is the router:

- The gateway answers ARP for its address (`10.0.0.1/24` by default). A packet a CC computer sends it goes into the
  TUN device. The server then takes it (its own address on the device is the gateway's), or forwards it.
- A packet the server sends into the TUN device goes onward by its destination:
  - **On the gateway's network:** straight to that computer.
  - **Covered by one of the gateway's routes** (for example `10.1.0.0/16 via 10.0.0.2`): to that CC router.
  - **Neither:** dropped.

  The gateway asks ARP for the MAC first. Packets wait meanwhile (3 tries, a second apart), and are dropped if no
  answer comes.
- Packets are converted field for field, so real stacks and ccnix's TCP talk as equals:
  - **Real to CC:** TCP options coming in are skipped, and none go out, so real peers keep to 536-byte segments and
    don't scale windows.
  - **What crosses:** TCP, UDP with a string payload, ICMP echo, unreachable and time exceeded.
  - **Dropped:** fragments, IPv6, and UDP payloads that are Lua tables (those stay on the CC network).
- Every gateway block in the world is a port of the one gateway. A frame for a MAC goes out of the block it was last
  heard from; broadcasts go out of all of them.
- The TUN device is held by a small helper process, `ccnix-tun`, which the mod starts and talks to over pipes. The
  Minecraft server needs no privileges. The helper needs none either, once the device belongs to the server's user.
  The helper starts with the first gateway block loaded, so a world without one runs no helper.

#### Setting up a server

The server has to be Linux, for the TUN device. Set `gateway.mode = "tun"`. [doc/ubuntu-server.md](doc/ubuntu-server.md) walks through a
fresh Ubuntu VM with the dma.space modpack, your CurseForge client, and the gateway with a CC router and a VLAN behind
it, end to end.

1. **Build the helper** and put it on the server's path:

   ```sh
   cc -O2 -Wall -o /usr/local/bin/ccnix-tun helper/ccnix-tun.c
   ```

2. **Make the device** as root, for the user the server runs as. Give it the gateway's address, and the networks CC
   routers will have behind them:

   ```sh
   sudo helper/ccnix-tun-setup minecraft 10.0.0.1/24 10.1.0.0/16
   ```

   - The networks go in a routing table of their own (4040, or `TABLE=`), which the host's own routes come before
     (all but its default route). So they can't take the host's LAN, its gateway or any route it has: they only
     stand in for the default route, for the addresses in them. Every route the gateway has, ops' too, has to be
     inside one of them. `ip route show table 4040` lists them.

   - `FORWARD=1` lets the server route between CC's networks and its own.
   - `NAT=eth0` also masquerades CC's networks going out of `eth0`. With it, CC computers can reach the real
     network without anything else knowing about them.
   - The device goes when the machine reboots, so run the script at every boot. A systemd unit:

   ```ini
   [Unit]
   Description=TUN device for ccnix's Network Gateway
   Before=minecraft.service

   [Service]
   Type=oneshot
   RemainAfterExit=yes
   Environment=NAT=eth0
   ExecStart=/usr/local/sbin/ccnix-tun-setup minecraft 10.0.0.1/24 10.1.0.0/16

   [Install]
   WantedBy=multi-user.target
   ```

3. **Install the mod.** Build it with `./gradlew build`, then put `build/libs/ccnix-<version>.jar` in the server's
   `mods/`. Clients need it too, for the block. It needs CC: Tweaked 1.120 or later.

4. **Configure it** in `config/ccnix-server.toml` in the server's folder (NeoForge 21.1 keeps server configs there).
   Edits take effect while the server runs.

   | Key | Default | What |
   |---|---|---|
   | `gateway.mode` | `nat` | `nat` or `tun` (above). |
   | `gateway.networks` | `["10.1.0.0/16"]` | The CC networks behind routers that the gateway carries; every route must be inside one. In TUN mode, give `ccnix-tun-setup` the same. |
   | `gateway.address` | `10.0.0.1/24` | The gateway's address and its network. In TUN mode, use the device's address. |
   | `gateway.channel` | `4040` | The modem channel: 4040 is ccnix's untagged network, 4040 + n is VLAN n. |
   | `gateway.mac` | `gateway` | Its MAC on the CC network. |
   | `gateway.routes` | `[]` | Networks behind CC routers: `["10.1.0.0/16 via 10.0.0.2"]`. The longest match wins. |
   | `gateway.opRoutes` | `false` | Ops may change the routes in game (`/ccnix route add\|del`). |
   | `gateway.opMaxSubnet` | `16` | The largest network an op may route, as a prefix length: 16 allows a /16 or smaller. |
   | `nat.*` | | NAT mode: `deny`, `allow`, `maxPerComputer`, `maxTotal`, `tcpIdle`, `udpIdle`, `dnsRelay`, `forwards`, `opForwards`, `opForwardPorts` (above). |
   | `tun.helper` | `ccnix-tun` | The helper, with any arguments before the device's name. |
   | `tun.device` | `ccnix0` | The TUN device. |

5. **Place a Network Gateway** and cable it to CC routers' wired modems. It's crafted from a full wired modem, iron,
   stone and redstone, and found in the Functional Blocks tab. `/ccnix` (operators) shows the gateway too:
   - its mode, address, routes and blocks;
   - the neighbours it knows;
   - packets carried and dropped;
   - the helper's state.

   The server log has the helper's messages.

6. **Routes in game** (either mode). `/ccnix route` shows the gateway's routes, its CC networks, and in TUN mode
   the server's routes into the device. With
   `gateway.opRoutes` on, ops (permission level 3) change them while the server runs, and the change is saved in the
   config:

   ```
   /ccnix route add 10.2.0.0/16 via 10.0.0.3
   /ccnix route del 10.2.0.0/16
   ```

   A route an op adds has to be a network's own address, no larger than `gateway.opMaxSubnet`, behind a router on
   the gateway's network, clear of the gateway's own network, inside `gateway.networks`, and in TUN mode inside a
   network the server routes into the device. It only tells the gateway which CC router has that network: nothing an
   op does reaches the server's own routing, and no part of the game needs root.

### On the CC side

A router with a wired modem on the gateway's cable (`back`), and its own network on another modem:

```
ip addr add back 10.0.0.2/24
ip addr add left 10.1.0.1/24
ip route add default via 10.0.0.1
router boot
```

Hosts behind it route at it (`ip route add default via 10.1.0.1`). The gateway needs a route back:
`routes = ["10.1.0.0/16 via 10.0.0.2"]`. A host can also sit on the gateway's network itself, with
`ip route add default via 10.0.0.1`.

In TUN mode, `telnet 10.1.0.6` from the server reaches a CC computer running `telnetd`. Other machines reach CC's
networks in one of two ways:
- through the server, with a route to them on your real router and `FORWARD=1`;
- or only outbound, with `NAT`.

In NAT mode, the outside reaches a CC computer through a port forward: `telnet <server> 25571`.

## Building and testing

`./gradlew build` builds and tests it. It needs Java 21, which Gradle fetches if it's missing, and git.

The build needs ccnix's Lua: the repo at the `ccnix/` submodule. Clone with
`git clone --recurse-submodules https://github.com/djt-code/ccnix-mod`, or run `git submodule update --init ccnix` in a
clone. With the submodule not checked out, the build uses the ccnix repo this is the `mod/` submodule of, or one at
`../ccnix`.
- **The ROM** is that repo at the submodule's commit, taken with `git archive`, so uncommitted edits and line endings
  stay out. For a newer ROM, `git submodule update --remote ccnix`, then commit the new pointer. `rom_url` and
  `rom_branch` in `gradle.properties` say where computers pull updates from; they go into the jar as
  `ccnix-rom.properties`, with the commit and its time, and the ROM's `.manifest` is built from that file and the
  files' blobs.
- **Each repo is a submodule of the other.** ccnix's `mod/` (this repo) is set `update = none`, so recursive submodule
  commands stop there instead of going round for ever. In ccnix, `git submodule update --init --checkout mod` gets it.
- **The tests** run ccnix's own `lib/net.lua` in Cobalt, CC's Lua VM. They cover:
  - **The ROM:** the jar's ROM is the submodule's `HEAD`, each file's blob SHA matches `git ls-tree`'s, its files keep
    to the ROM's folders, and a checkout is read right, with or without `git`.
  - **Conversion:** both ways, checked against packets built separately, with Linux's SYN options and IP options.
  - **The gateway:** ARP, routes, ports, retries and expiry.
  - **End to end:** CC computers ping, TCP and UDP to each other through the gateway and a fake kernel. A scripted
    "real" client then connects to a CC listener the way Linux would: its SYN options, sequence numbers wrapping
    past 2^32, binary data, both halves of the close, a refused port, ping, and an ICMP port unreachable.
  - **NAT mode:** CC computers through the gateway and NAT to real sockets on loopback: TCP both ways and closed in
    order, a refused port, UDP, the deny list and limits, idle connections, TCP and UDP forwards (the client's
    address), the DNS relay (and a query to another resolver left alone), the Windows answer; ops' forward checks.

`./gradlew runServer` runs a dev server with CC: Tweaked in `run/`.

## Not yet

- **Files deleted upstream** stay in the ROM, under a computer's updates, until the server's copy catches up.
- **No DHCP:** routers' addresses are set by hand.
- **No ICMP errors from the gateway itself in TUN mode.** A packet it can't deliver is just dropped. The gateway
  shares the server's address, and the server would refuse an ICMP from its own address. (NAT mode sends its own:
  net and port unreachable, time exceeded.)
- **IPv4 only**, and one gateway per server.
