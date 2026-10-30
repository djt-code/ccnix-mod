# ccnix

A Minecraft mod (NeoForge 1.21.1, with CC: Tweaked) that joins ComputerCraft's wired network to a real one. The
**Network Gateway** block sits on CC cables. CC computers running
[cc-turtles](https://github.com/djt-code/cc-turtles)' network stack (`lib/net.lua`) route at it, and their packets
come out of a TUN device on the server as real IPv4. Packets the server sends into that device go to the CC computer
they're for.

So a real machine can `telnet` to a CC computer running `telnetd`, `ping` it, or `nc` to it. A CC computer can reach
anything the server can reach.

```
 CC host 10.0.0.5 ─┐
                   ├─ cable ─ [Network Gateway] ··· ccnix-tun ─ ccnix0 (10.0.0.1) ─ the server's kernel ─ eth0
 CC router 10.0.0.2┘   10.0.0.1, MAC "gateway",     (helper,
   └ 10.1.0.0/16 behind it   channel 4040         no privileges)
```

## How it works

The **gateway** is the server's link onto the CC network, and the **server** is the router:

- The gateway answers ARP for its address (`10.0.0.1/24` by default). A packet a CC computer sends it goes into the
  TUN device. The server then takes it (its own address on the device is the gateway's), or forwards it.
- A packet the server sends into the TUN device goes onward by its destination:
  - **On the gateway's network:** straight to that computer.
  - **Covered by one of the gateway's routes** (for example `10.1.0.0/16 via 10.0.0.2`): to that CC router.
  - **Neither:** dropped.

  The gateway asks ARP for the MAC first. Packets wait meanwhile (3 tries, a second apart), and are dropped if no
  answer comes.
- Packets are converted field for field, so real stacks and cc-turtles' TCP talk as equals:
  - **Real to CC:** TCP options coming in are skipped, and none go out, so real peers keep to 536-byte segments and
    don't scale windows.
  - **What crosses:** TCP, UDP with a string payload, ICMP echo, unreachable and time exceeded.
  - **Dropped:** fragments, IPv6, and UDP payloads that are Lua tables (those stay on the CC network).
- Every gateway block in the world is a port of the one gateway. A frame for a MAC goes out of the block it was last
  heard from; broadcasts go out of all of them.
- The TUN device is held by a small helper process, `ccnix-tun`, which the mod starts and talks to over pipes. The
  Minecraft server needs no privileges. The helper needs none either, once the device belongs to the server's user.
  The helper starts with the first gateway block loaded, so a world without one runs no helper.

## Setting up a server

The server has to be Linux, for the TUN device.

1. **Build the helper** and put it on the server's path:

   ```sh
   cc -O2 -Wall -o /usr/local/bin/ccnix-tun helper/ccnix-tun.c
   ```

2. **Make the device** as root, for the user the server runs as. Give it the gateway's address, and a route for CC's
   networks behind routers:

   ```sh
   sudo helper/ccnix-tun-setup minecraft 10.0.0.1/24 10.1.0.0/16
   ```

   - `FORWARD=1` lets the server route between CC's networks and its own.
   - `NAT=eth0` also masquerades CC's networks going out of `eth0`. With it, CC computers can reach the real
     network without anything else knowing about them.
   - The device goes when the machine reboots, so run the script at every boot. A systemd unit:

   ```ini
   [Unit]
   Description=TUN device for the ccnix
   Before=minecraft.service

   [Service]
   Type=oneshot
   RemainAfterExit=yes
   Environment=NAT=eth0
   ExecStart=/usr/local/sbin/ccnix-tun-setup minecraft 10.0.0.1/24 10.0.0.0/8

   [Install]
   WantedBy=multi-user.target
   ```

3. **Install the mod.** Build it with `./gradlew build`, then put `build/libs/ccnix-<version>.jar` in the
   server's `mods/`. Clients need it too, for the block. It needs CC: Tweaked 1.120 or later.

4. **Configure it** in `world/serverconfig/ccnix-server.toml`. Put a copy in `defaultconfigs/` to give new
   worlds the same settings. Edits take effect while the server runs.

   | Key | Default | What |
   |---|---|---|
   | `gateway.address` | `10.0.0.1/24` | The gateway's address and its network. Use the device's address. |
   | `gateway.channel` | `4040` | The modem channel: 4040 is cc-turtles' untagged network, 4040 + n is VLAN n. |
   | `gateway.mac` | `gateway` | Its MAC on the CC network. |
   | `gateway.routes` | `[]` | Networks behind CC routers: `["10.1.0.0/16 via 10.0.0.2"]`. The longest match wins. |
   | `tun.helper` | `ccnix-tun` | The helper, with any arguments before the device's name. |
   | `tun.device` | `ccnix0` | The TUN device. |

5. **Place a Network Gateway** and cable it to CC routers' wired modems. It's crafted from a full wired modem, iron,
   stone and redstone, and found in the Functional Blocks tab. `/gateway` (operators) shows:
   - its address, routes and blocks;
   - the neighbours it knows;
   - packets carried and dropped;
   - the helper's state.

   The server log has the helper's messages.

## On the CC side

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

From the server, `telnet 10.1.0.6` reaches a CC computer running `telnetd`. Other machines reach CC's networks in one
of two ways:
- through the server, with a route to them on your real router and `FORWARD=1`;
- or only outbound, with `NAT`.

## Building and testing

`./gradlew build` builds and tests it. It needs Java 21, which Gradle fetches if it's missing.

The tests run cc-turtles' own `lib/net.lua` in Cobalt, CC's Lua VM. It's found at `../lib/net.lua` when this repo is
cc-turtles' `mod/` submodule, or at `../cc-turtles/lib/net.lua` beside it. They cover:

- **Conversion:** both ways, checked against packets built separately, with Linux's SYN options and IP options.
- **The gateway:** ARP, routes, ports, retries and expiry.
- **End to end:** CC computers ping, TCP and UDP to each other through the gateway and a fake kernel. A scripted
  "real" client then connects to a CC listener the way Linux would: its SYN options, sequence numbers wrapping past
  2^32, binary data, both halves of the close, a refused port, ping, and an ICMP port unreachable.

`./gradlew runServer` runs a dev server with CC: Tweaked in `run/`.

## Not yet

- **No DHCP:** routers' addresses are set by hand.
- **No ICMP errors from the gateway itself.** A packet it can't deliver is just dropped. The gateway shares the
  server's address, and the server would refuse an ICMP from its own address.
- **IPv4 only**, and one gateway per server.
