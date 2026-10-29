/*
 * craftix-tun: carry IPv4 packets between a TUN device and stdin/stdout,
 * for the Craftix Gateway mod.  The mod runs it, so the Minecraft server
 * itself needs no privileges.
 *
 *   craftix-tun DEVICE
 *
 * Each packet crosses the pipes as two bytes of length (big-endian), then
 * the packet.  The device is TUN with no packet information (IFF_TUN |
 * IFF_NO_PI): bare IP packets both ways.
 *
 * Attaching to a device that exists and belongs to this user (ip tuntap
 * add DEVICE mode tun user USER, which craftix-tun-setup does) needs no
 * privileges.  Without one, the kernel makes the device if this has
 * CAP_NET_ADMIN (setcap cap_net_admin+ep craftix-tun), but it has no
 * address and goes when this exits: set it up instead.
 *
 * It exits when stdin closes (the server stopped), and on any error with
 * the device; the mod starts it again.
 *
 * Build: cc -O2 -Wall -o craftix-tun craftix-tun.c
 */
#include <errno.h>
#include <fcntl.h>
#include <linux/if.h>
#include <linux/if_tun.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static const char *me = "craftix-tun";

static void die(const char *what)
{
    int e = errno;
    fprintf(stderr, "%s: %s: %s%s\n", me, what, strerror(e),
            e == EPERM ? " (run craftix-tun-setup for this user, or give craftix-tun CAP_NET_ADMIN)" : "");
    exit(1);
}

/* Read exactly n bytes from fd: 1, or 0 at the end or on an error. */
static int readall(int fd, unsigned char *buf, size_t n)
{
    while (n > 0) {
        ssize_t r = read(fd, buf, n);
        if (r < 0 && errno == EINTR)
            continue;
        if (r <= 0)
            return 0;
        buf += r;
        n -= (size_t)r;
    }
    return 1;
}

static int writeall(int fd, const unsigned char *buf, size_t n)
{
    while (n > 0) {
        ssize_t w = write(fd, buf, n);
        if (w < 0 && errno == EINTR)
            continue;
        if (w <= 0)
            return 0;
        buf += w;
        n -= (size_t)w;
    }
    return 1;
}

int main(int argc, char **argv)
{
    static unsigned char up[2 + 65535], down[65535];
    struct ifreq ifr;
    unsigned long bad = 0;
    int tun;

    if (argc != 2 || strlen(argv[1]) >= IFNAMSIZ) {
        fprintf(stderr, "usage: %s DEVICE\n", me);
        return 2;
    }
    tun = open("/dev/net/tun", O_RDWR);
    if (tun < 0)
        die("/dev/net/tun");
    memset(&ifr, 0, sizeof ifr);
    ifr.ifr_flags = IFF_TUN | IFF_NO_PI;
    strncpy(ifr.ifr_name, argv[1], IFNAMSIZ - 1);
    if (ioctl(tun, TUNSETIFF, &ifr) < 0)
        die(argv[1]);
    fprintf(stderr, "%s: attached to %s\n", me, ifr.ifr_name);

    struct pollfd fds[2] = {
        { .fd = tun, .events = POLLIN },
        { .fd = 0, .events = POLLIN },
    };
    for (;;) {
        if (poll(fds, 2, -1) < 0) {
            if (errno == EINTR)
                continue;
            die("poll");
        }
        if (fds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) {
            fprintf(stderr, "%s: %s went away\n", me, ifr.ifr_name);
            return 1;
        }
        if (fds[0].revents & POLLIN) {          /* the kernel to the mod */
            ssize_t n = read(tun, up + 2, sizeof up - 2);
            if (n < 0 && errno != EINTR && errno != EAGAIN)
                die("reading the device");
            if (n > 0) {
                up[0] = (unsigned char)(n >> 8);
                up[1] = (unsigned char)n;
                if (!writeall(1, up, (size_t)n + 2))
                    return 0;
            }
        }
        if (fds[1].revents & (POLLIN | POLLHUP | POLLERR)) {   /* the mod to the kernel */
            unsigned char len[2];
            size_t n;
            if (!readall(0, len, 2))
                return 0;
            n = (size_t)len[0] << 8 | len[1];
            if (!readall(0, down, n))
                return 0;
            if (n > 0 && write(tun, down, n) < 0 && ++bad <= 10)
                fprintf(stderr, "%s: writing to %s: %s%s\n", me, ifr.ifr_name, strerror(errno),
                        bad == 10 ? " (no more of these)" : "");
        }
    }
}
