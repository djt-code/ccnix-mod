# A test server on Ubuntu, and the gateway end to end

A fresh Ubuntu (26.04 or thereabouts) VM, a Minecraft server with the ccnix
mod on it, and a CC network behind a CC router on a VLAN, reached from the
VM with `ping`, `telnet` and `nc`. Everything here runs as root unless it
says `minecraft`. Versions: NeoForge 21.1.252, CC: Tweaked 1.120.2, Java 21.

## 1. The VM

```sh
apt update && apt install -y openjdk-21-jdk-headless git gcc tmux curl iptables
adduser --system --group --home /srv/minecraft --shell /bin/bash minecraft
ufw allow 25565/tcp        # if ufw is on
ip -br link               # note the uplink's name (eth0, enp0s3...): NAT below wants it
```

Java 21 is what NeoForge 1.21.1 needs; the JDK (not just the JRE) because
the mod is built here too.

## 2. The mod and the helper

```sh
su - minecraft
git clone --recurse-submodules https://github.com/djt-code/ccnix-mod
cd ccnix-mod && ./gradlew build          # ~5 min the first time (NeoForge, CC:T, Gradle itself)
ls build/libs/ccnix-0.1.0.jar            # the mod, for the server and every client
exit
```

(`--recurse-submodules` brings the `ccnix/` submodule the ROM is built from;
its own `mod/` submodule is `update = none`, so it stops there. Or build on
Windows with `.\gradlew build` and `scp` the jar.)

The helper holds the TUN device for the server:

```sh
cd /srv/minecraft/ccnix-mod
cc -O2 -Wall -o /usr/local/bin/ccnix-tun helper/ccnix-tun.c
install -m 755 helper/ccnix-tun-setup /usr/local/sbin/
```

## 3. The device

The gateway is `10.0.0.1/24`; CC networks behind CC routers live in
`10.1.0.0/16`. `NAT=<uplink>` lets CC computers reach past the VM (the real
network needn't know about them); leave it off for a first run.

```sh
NAT=eth0 ccnix-tun-setup minecraft 10.0.0.1/24 10.1.0.0/16
ip -br addr show ccnix0                  # UNKNOWN 10.0.0.1/24
ip route | grep ccnix0                   # 10.0.0.0/24 and 10.1.0.0/16 dev ccnix0
```

The device goes at reboot. A unit that remakes it before the server:

```ini
# /etc/systemd/system/ccnix-tun.service
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

`systemctl enable --now ccnix-tun`.

## 4. The server

```sh
su - minecraft
mkdir server && cd server
curl -LO https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.252/neoforge-21.1.252-installer.jar
java -jar neoforge-21.1.252-installer.jar --install-server .
mkdir mods
curl -Lo mods/cc-tweaked-1.21.1-forge-1.120.2.jar \
  https://maven.squiddev.cc/cc/tweaked/cc-tweaked-1.21.1-forge/1.120.2/cc-tweaked-1.21.1-forge-1.120.2.jar
cp ../ccnix-mod/build/libs/ccnix-0.1.0.jar mods/
echo 'eula=true' > eula.txt
echo '-Xms2G -Xmx4G' > user_jvm_args.txt
tmux new -s mc ./run.sh nogui
```

(CC: Tweaked's "forge" build is the NeoForge one for 1.21.1; Modrinth has
the same jar.) In the console once the world is up: `op <your name>`, so
`/ccnix` works in game. Watch the log for `ccnix`: it says which ROM is in
use (the jar's, at ccnix commit so-and-so). Detach with Ctrl+B D; `tmux
attach -t mc` comes back.

For later, a unit (`systemctl enable --now minecraft`; the console is then
`/ccnix` in game, or rcon):

```ini
# /etc/systemd/system/minecraft.service
[Unit]
Description=Minecraft (NeoForge, ccnix)
After=network.target ccnix-tun.service
Wants=ccnix-tun.service

[Service]
User=minecraft
WorkingDirectory=/srv/minecraft/server
ExecStart=/srv/minecraft/server/run.sh nogui
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

**The client** needs NeoForge 21.1.x, CC: Tweaked 1.120.2 and the same
`ccnix-0.1.0.jar` in its `mods/`, for the gateway block.

**A live ROM** (to test ccnix changes without rebuilding the jar): in the
server folder, `git clone https://github.com/djt-code/ccnix ccnix-rom`;
after a `git -C ccnix-rom pull`, `/reload` in game. Delete the folder and
`/reload` to go back to the jar's.

## 5. The gateway's config

After the first world load: `world/serverconfig/ccnix-server.toml`. Edits
apply while the server runs.

```toml
[gateway]
    address = "10.0.0.1/24"
    channel = 4040
    mac = "gateway"
    routes = ["10.1.0.0/16 via 10.0.0.2"]      # the CC router below
[tun]
    helper = "ccnix-tun"
    device = "ccnix0"
```

The helper starts with the first gateway block loaded; the log says so, or
"helper: ..." with what went wrong (no device: run the setup script; not
`minecraft`'s: run it with the right user).

## 6. In game: the gateway's network

Build, on one cable:

```
 [Network Gateway] ─ networking cable ─ wired modem on R's BACK
```

R is a computer (advanced is nicer). The gateway is in the Functional
Blocks tab (recipe: a full wired modem, iron, stone, redstone; or
`/give @s ccnix:gateway`). Place R's wired modem on its back and run cable
to the gateway; the modem needs no right-click.

On R (everything is in the ROM already; `help ip` and `help network` are
there too):

```
ip addr add back 10.0.0.2/24
ip route add default via 10.0.0.1
router boot                  routing from every boot; this shows the screen (q leaves it)
ping 10.0.0.1                the VM answers: it's the gateway's address on ccnix0
```

On the VM: `ping 10.0.0.2` answers from R. `/ccnix` in game shows the
gateway's blocks, R as a neighbour, and packets carried. If `ping` says
"host unreachable": R's `ip neigh` has no `gateway`; check the cable touches
both the modem and the gateway block, and the channel is 4040 on both.

## 7. In game: a VLAN behind R

A second cable, from a wired modem on R's LEFT, to two more computers A and
B with wired modems. That cable carries VLAN 20, network `10.1.0.0/24`,
with R as its router. (The tag is what lets one cable carry several
networks: VLAN 30 on the same wire would be another, `left.30`, that A and
B can't hear. `help network` has the trunk variant with everything on one
cable, the gateway's included.)

```
 R: ip addr add left.20 10.1.0.1/24           (router: `router` restarts its screen; forwarding is on)
 A: ip addr add back.20 10.1.0.6/24
    ip route add default via 10.1.0.1
    ip boot                                   (up at every boot)
    ip up
 B: the same with 10.1.0.7
```

Test the inside first, from A: `ping 10.1.0.1` (R), `ping 10.0.0.2` (R's
other side), `ping 10.0.0.1` (the VM, through R and the gateway: ttl=62),
`traceroute 10.0.0.1` (10.1.0.1, then 10.0.0.1). With NAT on: `ping
1.1.1.1`.

Then from the VM:

```sh
ping 10.1.0.6                 # answers; ttl=63 (R took one)
traceroute -n 10.1.0.6        # 10.0.0.2, then 10.1.0.6   (apt install traceroute)
```

`traceroute` from a real stack works because R answers time-exceeded, and
the gateway converts it. If `ping 10.1.0.6` says nothing: the gateway has
no route for 10.1.0.0/16 (section 5), or the VM has none for it on `ccnix0`
(section 3); `/ccnix` counts the drop.

**TCP both ways.** On A: `nc -l 7`; on the VM: `nc 10.1.0.6 7`, type: the
lines show on A and A's on the VM. The other way: on the VM `nc -l 7000`,
on A `nc 10.0.0.1 7000`. And `nc 10.0.0.1 22` on A prints the VM's SSH
banner.

**Telnet into A.** On A:

```
set telnetd.password pick-one
set init.boot true
service add telnetd telnetd
service enable telnetd
reboot
```

(`wget run https://raw.githubusercontent.com/djt-code/ccnix/main/deploy/setup.lua telnetd`
does the same, HTTP being on.) From the VM: `telnet 10.1.0.6`, the
password, and A's shell is on your terminal with ANSI colours; `exit`
leaves. `set telnetd.attach console` on A shows A's own screen instead
(a miner's, say).

**From your desktop**, not just the VM: `FORWARD=1` (or `NAT`) in the setup
script, and on the desktop a route to CC's networks through the VM, e.g.
Windows `route add 10.0.0.0 mask 255.0.0.0 <vm address>`; then `telnet
10.1.0.6` from there.

## 8. When it doesn't

| Where | Shows |
|---|---|
| `/ccnix` (in game, op) | ROM in use; the gateway's address, routes, blocks, neighbours, packets carried and dropped, the helper's state |
| server log | `ccnix:` lines: the ROM, the helper starting, its messages |
| R: `router` | each interface's frames, routes, forwarded/dropped, "no ARP answer" and such |
| a host: `ip`, `ip neigh` | what's set, whether it's up, who ARP has found |
| VM: `ip -br addr show ccnix0`, `ip route`, `tcpdump -ni ccnix0` | the device, the routes into it, the packets actually crossing |
| `ping` says | "net unreachable": a router lacks a route; "host unreachable": no ARP answer (address wrong, or not up); "time to live exceeded": a routing loop |
