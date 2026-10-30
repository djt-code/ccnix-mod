package ccnix.rom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RomTest {
    @Test
    void blobShaIsGits() {
        // git hash-object of "hello\n"
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", Rom.blobSha("hello\n".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", Rom.blobSha(new byte[0]));
    }

    @Test
    void theJarsRomIsCcnixHead() throws Exception {
        var rom = Rom.fromJar(getClass().getClassLoader());
        var repo = Path.of(System.getProperty("ccnix.net")).getParent().getParent();
        assertEquals(git(repo, "rev-parse", "HEAD").trim(), rom.commit);
        assertTrue(rom.date > 1_700_000_000L, "a commit time: " + rom.date);
        assertEquals("https://github.com/djt-code/ccnix", rom.url);
        assertEquals("main", rom.branch);
        assertTrue(rom.files.containsKey("deploy/boot.lua") && rom.files.containsKey("bin/ccnix.lua")
            && rom.files.containsKey("lib/net.lua") && rom.files.containsKey("help/ccnix.txt"));
        assertEquals(List.of("bin/[.lua"), rom.leftOut, "[ has no resource path");

        // every blob as git names it: what GitHub's tree gives, so ccnix update skips just these
        var tree = new HashMap<String, String>();
        var lsTree = new ArrayList<>(List.of("ls-tree", "-r", "HEAD", "--"));
        lsTree.addAll(Rom.DIRS);
        for (var line : git(repo, lsTree.toArray(String[]::new)).split("\n")) {
            if (line.isBlank()) continue;
            var tab = line.indexOf('\t');
            tree.put(line.substring(tab + 1), line.substring(0, tab).split(" ")[2]);
        }
        assertEquals(tree.size() - 1, rom.files.size());
        rom.files.forEach((path, data) -> assertEquals(tree.get(path), Rom.blobSha(data), path));
        var manifest = new String(rom.manifest(), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"commit\":\"" + rom.commit + "\""), manifest.substring(0, 200));
        assertTrue(manifest.contains("\"deploy/boot.lua\":\"" + tree.get("deploy/boot.lua") + "\""));
        assertFalse(manifest.contains("[.lua"));
    }

    @Test
    void theIndexKeepsToRomDirs() throws Exception {
        // build.gradle's romPaths fills the jar's ROM and Rom.DIRS reads a checkout's: the same folders, or a file
        // would come from one ROM and not the other
        String index;
        try (var in = getClass().getClassLoader().getResourceAsStream("ccnix-rom/.index")) {
            index = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var paths = index.lines().filter(l -> !l.isBlank()).toList();
        assertFalse(paths.isEmpty());
        for (var path : paths) assertTrue(Rom.DIRS.stream().anyMatch(d -> path.startsWith(d + "/")), path);
        for (var d : Rom.DIRS) assertTrue(paths.stream().anyMatch(p -> p.startsWith(d + "/")), d + "/ is in the jar");
    }

    @Test
    void aCheckout(@TempDir Path dir) throws Exception {
        write(dir, "bin/a.lua", "print('a')\n");
        write(dir, "bin/[.lua", "-- [\n");
        write(dir, "lib/x.lua", "return {}\n");
        write(dir, "sim/s.lua", "-- not in the ROM\n");
        write(dir, "README.md", "# not in the ROM\n");
        write(dir, ".git/HEAD", "ref: refs/heads/main\n");
        write(dir, ".git/packed-refs", "# pack-refs\n0123456789abcdef0123456789abcdef01234567 refs/heads/main\n");
        var jar = Rom.fromJar(getClass().getClassLoader());
        var rom = Rom.fromCheckout(dir, jar);
        assertEquals(List.of("bin/a.lua", "lib/x.lua"), List.copyOf(rom.files.keySet()));
        assertEquals(List.of("bin/[.lua"), rom.leftOut);
        assertEquals("0123456789abcdef0123456789abcdef01234567", rom.commit, "from .git when git can't say");
        assertEquals(jar.url, rom.url, "no origin: the jar's repo");
        assertTrue(rom.source.startsWith("the checkout "));
        assertTrue(new String(rom.manifest(), StandardCharsets.UTF_8)
            .contains("\"bin/a.lua\":\"" + Rom.blobSha("print('a')\n".getBytes(StandardCharsets.UTF_8)) + "\""));
    }

    @Test
    void urls() {
        assertEquals("https://github.com/djt-code/ccnix", Rom.httpsUrl("git@github.com:djt-code/ccnix.git"));
        assertEquals("https://git.example.net/me/ccnix", Rom.httpsUrl("https://git.example.net/me/ccnix.git"));
        assertNull(Rom.httpsUrl("git@myhost:me/ccnix.git"), "an ssh alias: no way to know its web address");
        assertNull(Rom.httpsUrl(null));
    }

    @Test
    void servable() {
        assertTrue(Rom.servable("bin/ls.lua"));
        assertTrue(Rom.servable("help/a-b_c.txt"));
        assertFalse(Rom.servable("bin/[.lua"));
        assertFalse(Rom.servable("LICENSE"));
    }

    private static void write(Path dir, String path, String text) throws IOException {
        var p = dir.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private static String git(Path dir, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        var p = new ProcessBuilder(cmd).start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "git " + String.join(" ", args));
        return out;
    }
}
