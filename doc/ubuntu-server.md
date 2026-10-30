# A dev server on Ubuntu with the dma.space pack, and the gateway end to end

An Ubuntu (26.04) VM running the dma.space modpack's server with the ccnix
mod, your desktop's CurseForge instance as the client, and then a CC
network behind a CC router on a VLAN, reached from the VM with `ping`,
`telnet` and `nc`.

Versions, from the pack (DMA 1.5, 2026-10-30): Minecraft 1.21.1, NeoForge
21.1.256, Java 21, CC: Tweaked 1.120.2. The server's mod set below was
booted to "Done" with ccnix in it before this was written.

Where it says **desktop**, that's Git Bash on Windows; **VM** is a root
shell unless it says `minecraft`.

**Sections 3, 4 and 6 are automated** by [../ansible/](../ansible/README.md):
after section 2's bundle, `ansible/run-on-host.sh` (or `ansible-playbook
site.yml`) does them, with the server as the `minecraft` service
(`mc-console`, `mc-cmd`). What follows is what it does, by hand.

## 1. The client: CC: Tweaked 1.120.2, and ccnix

The pack lists CC: Tweaked **1.113.1**, but its own Advanced Peripherals
needs 1.119 or later and cc_sable 1.117.1 or later (ccnix: 1.120). What
saves it is `cc-tweaked-1.21.1-forge-1.120.2.jar` beside it in `mods/`:
with two jars of one mod, NeoForge loads the highest version and ignores
the other (the log lists both under "Found mod file"; the in-game Mods
list shows 1.120.2). So:

1. Keep `cc-tweaked-1.21.1-forge-1.120.2.jar` in the instance's `mods/`.
   It's from another CurseForge entry, so CurseForge shows only the
   pack's 1.113.1; if it's ever gone, get it from Modrinth or
   `https://maven.squiddev.cc/cc/tweaked/cc-tweaked-1.21.1-forge/1.120.2/`.
   Turning 1.113.1 off in CurseForge is tidier but not needed.
2. Build ccnix and put it in the instance:

   ```sh
   # desktop
   cd ~/Projects/ccnix-mod && git pull && ./gradlew build
   cp build/libs/ccnix-0.2.0.jar "<your CurseForge instance folder>/mods/"
   ```

After a pack update, check the Mods screen (title screen → Mods → CC:
Tweaked) still says 1.120.2: an update that removed the extra jar would
leave 1.113.1, and Advanced Peripherals would refuse to load.

Launch the instance once to check it loads, then leave it for section 5.

## 2. The server's files, from the instance

The pack has no server pack, so the server's mods come from the client
instance: all of them less **Sodium** (a client renderer: it stops a
dedicated server before any mod loads) and the unused CC: Tweaked 1.113.1, plus
ccnix. `helper/server-bundle.sh` makes that set, with the instance's
`config/` and `defaultconfigs/`:

```sh
# desktop
cd ~/Projects/ccnix-mod
helper/server-bundle.sh "<your CurseForge instance folder>" server-bundle.tar.gz
#   left out: cc-tweaked-1.21.1-forge-1.113.1.jar (CC: Tweaked older than 1.120)
#   left out: sodium-neoforge-0.8.13+mc1.21.1.jar (client-only)
#   server-bundle.tar.gz: 125 mods, with ccnix-0.2.0.jar
scp server-bundle.tar.gz helper/ccnix-tun.c helper/ccnix-tun-setup root@<vm>:/root/
```

(~250 MB.) If a later pack adds a client-only mod, the server log names
it ("invalid dist DEDICATED_SERVER", or a client class in the first stack
trace): add its jar's name prefix to `CLIENT_ONLY` in the script.

## 3. The VM

```sh
apt update && apt install -y openjdk-21-jre-headless gcc tmux curl iptables
java -version                                 # 21: NeoForge 1.21.1 wants exactly 21, not 25
adduser --system --group --home /srv/minecraft --shell /bin/bash minecraft
ufw allow 25565/tcp; ufw allow 24454/udp      # the game; Simple Voice Chat (if ufw is on)
ip -br link                                   # the uplink's name (eth0, enp0s3...), for NAT
```

At least **8 GB of RAM** for the VM: the server gets 6.

**The gateway's helper** (holds the TUN device):

```sh
cc -O2 -Wall -o /usr/local/bin/ccnix-tun /root/ccnix-tun.c
install -m 755 /root/ccnix-tun-setup /usr/local/sbin/
```

**The device.** The gateway is `10.0.0.1/24`; CC networks behind CC
routers live in `10.0.0.0/8`. `NAT=<uplink>` lets CC computers reach past
the VM; leave it off for a first run.

```sh
NAT=eth0 ccnix-tun-setup minecraft 10.0.0.1/24 10.0.0.0/8
ip -br addr show ccnix0                  # UNKNOWN 10.0.0.1/24
ip route show table 4040                 # 10.0.0.0/8 dev ccnix0
ip rule | grep 404                       # main without its default route first, then table 4040
```

CC's networks are in a table of their own, after the VM's own routes, so
they can't take its LAN or its gateway away from it.

It goes at reboot; this unit remakes it before the server
(`systemctl enable --now ccnix-tun`):

```ini
# /etc/systemd/system/ccnix-tun.service
[Unit]
Description=TUN device for ccnix's Network Gateway
Before=minecraft.service

[Service]
Type=oneshot
RemainAfterExit=yes
Environment=NAT=eth0
ExecStart=/usr/local/sbin/ccnix-tun-setup minecraft 10.0.0.1/24 10.0.0.0/8

[Install]
WantedBy=multi-user.target
```

## 4. The server

```sh
# VM
install -d -o minecraft -g minecraft /srv/minecraft/server
cd /srv/minecraft/server
tar -xzf /root/server-bundle.tar.gz && chown -R minecraft:minecraft .
su minecraft -c '
  curl -sLO https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.256/neoforge-21.1.256-installer.jar
  java -jar neoforge-21.1.256-installer.jar --install-server . && rm neoforge-21.1.256-installer.jar*
  echo eula=true > eula.txt
  echo "-Xms4G -Xmx6G" > user_jvm_args.txt
'
su minecraft -c 'tmux new -s mc ./run.sh nogui'
```

It's up at `Done (…s)! For help, type "help"`. The log on the way has
hundreds of ERROR lines about loot tables, data maps and recipes for mods
the pack doesn't have (railways' tracks for other mods' woods, and so on):
the pack's, harmless. What matters:

- `ccnix ROM: <commit> from the mod, 126 files`: the ROM is in.
- No `ccnix config:` warnings, and later `helper:` lines saying it started.

In the console: `op <you>`, so `/ccnix` works in game. Ctrl+B D detaches;
`su minecraft -c 'tmux attach -t mc'` comes back.

`server.properties` was written on the first start; `motd`, `view-distance`
and the like are yours. Keep `online-mode=true` (your Microsoft account).

**As a service**, once it's right (`systemctl enable --now minecraft`;
the console is then rcon, or `/` commands in game):

```ini
# /etc/systemd/system/minecraft.service
[Unit]
Description=Minecraft (dma.space + ccnix)
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

**Updating:** a new pack, or a new ccnix jar: on the desktop, section 1's
fix-ups, `./gradlew build`, `helper/server-bundle.sh` again; on the VM,
stop the server, `rm -rf mods`, untar (it brings `config/` again: keep a
copy of `config/ccnix-server.toml` first), start.

**A live ROM** (ccnix's Lua without rebuilding the jar): `/ccnix rom
update` in game (or `mc-cmd "ccnix rom update"`) clones the repo into
`ccnix-rom/` the first time, pulls it after, and reloads. Delete the
folder and `/reload` to go back to the jar's.

## 5. Joining from the desktop

CurseForge → dma.space → Play → Multiplayer → Add Server: `<vm>:25565`.
The client and server must have the same mod set, give or take client-only
mods: if the join fails with a mod mismatch, the screen names the mod;
usually CC: Tweaked if a pack update took the 1.120.2 jar away (section
1), or a ccnix jar older than the server's.

**Without the pack**, to work on the mod alone, `./gradlew runServer` and
`./gradlew runClient` in ccnix-mod run NeoForge with CC: Tweaked and ccnix
only; that client can't join the pack's server.

## 6. The gateway's config

`config/ccnix-server.toml` in the server's folder (NeoForge 21.1 keeps
server configs there, not under `world/serverconfig`). Edits apply while
the server runs.

```toml
[gateway]
    address = "10.0.0.1/24"
    channel = 4040
    mac = "gateway"
    routes = ["10.1.0.0/16 via 10.0.0.2"]      # the CC router below
    opRoutes = true                            # ops may /ccnix route add|del
    opMaxSubnet = 16                           # a /16 at most
[tun]
    helper = "ccnix-tun"
    device = "ccnix0"
```

The helper starts with the first gateway block loaded; the log says so, or
"helper: ..." with what went wrong (no device: run the setup script; not
`minecraft`'s: run it with the right user).

## 7. In game: the gateway's network

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

## 8. In game: a VLAN behind R

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
no route for 10.1.0.0/16 (section 6), or the VM has none for it on `ccnix0`
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

## 9. When it doesn't

| Where | Shows |
|---|---|
| `/ccnix` (in game, op) | ROM in use; the gateway's address, routes, blocks, neighbours, packets carried and dropped, the helper's state |
| server log | `ccnix:` lines: the ROM, the helper starting, its messages |
| R: `router` | each interface's frames, routes, forwarded/dropped, "no ARP answer" and such |
| a host: `ip`, `ip neigh` | what's set, whether it's up, who ARP has found |
| VM: `ip -br addr show ccnix0`, `ip route`, `tcpdump -ni ccnix0` | the device, the routes into it, the packets actually crossing |
| `ping` says | "net unreachable": a router lacks a route; "host unreachable": no ARP answer (address wrong, or not up); "time to live exceeded": a routing loop |
