package ccnix.rom;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * cc-turtles' programs as every computer's ROM has them, at /rom/ccnix: the copy built into the jar, or the
 * server's own git checkout ({@value #CHECKOUT} in the server's folder) when there is one, so the server's admin
 * updates it with git pull and /reload.
 * <p>
 * The ROM also gets a .manifest (the commit, its time, the repo and branch, and each file's git blob SHA-1) for
 * ccnix update on the computers, which keeps only what differs from it, and the autorun that boots it.
 */
public final class Rom {
    public static final String CHECKOUT = "ccnix-rom";
    public static final List<String> DIRS = List.of("bin", "lib", "turtle", "pc", "help", "deploy");
    public static final String AUTORUN = "shell.run(\"/rom/ccnix/deploy/boot.lua\")   -- ccnix: see that file\n";

    /** Where it came from, for /ccnix and the log. */
    public final String source;
    public final String commit, url, branch;
    /** The commit's time, seconds since 1970 (0 if unknown). */
    public final long date;
    /** Repo path to contents, for the files a ROM can hold. */
    public final Map<String, byte[]> files;
    /** Repo paths left out: names a resource path can't have ("[.lua", capitals). */
    public final List<String> leftOut;

    private Rom(String source, String commit, long date, String url, String branch, Map<String, byte[]> all) {
        this.source = source;
        this.commit = commit;
        this.date = date;
        this.url = url;
        this.branch = branch;
        var kept = new TreeMap<String, byte[]>();
        var out = new ArrayList<String>();
        all.forEach((path, data) -> {
            if (servable(path)) kept.put(path, data);
            else out.add(path);
        });
        this.files = kept;
        this.leftOut = out;
    }

    /** The checkout in the server's folder if there's one, else the jar's. */
    public static Rom load(Path serverDir, ClassLoader jar, java.util.function.Consumer<String> warn) throws IOException {
        var dir = serverDir.resolve(CHECKOUT);
        if (Files.isDirectory(dir.resolve("bin"))) {
            try {
                return fromCheckout(dir, fromJar(jar));
            } catch (IOException | RuntimeException e) {
                warn.accept("can't use " + dir + " (" + e + "): the mod's own ROM instead");
            }
        }
        return fromJar(jar);
    }

    /** The jar's: ccnix-rom/ with its .index, and ccnix-rom.properties. */
    public static Rom fromJar(ClassLoader jar) throws IOException {
        var props = new Properties();
        try (var in = open(jar, "ccnix-rom.properties")) {
            props.load(in);
        }
        Map<String, byte[]> all = new TreeMap<>();
        String index;
        try (var in = open(jar, "ccnix-rom/.index")) {
            index = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (var path : index.split("\n")) {
            if (path.isBlank()) continue;
            try (var in = open(jar, "ccnix-rom/" + path)) {
                all.put(path, in.readAllBytes());
            }
        }
        return new Rom("the mod", props.getProperty("commit", "unknown"), parseLong(props.getProperty("date")),
            props.getProperty("url"), props.getProperty("branch", "main"), all);
    }

    /**
     * A git checkout of cc-turtles: the files in its DIRS as they are on disk, and the commit and its time from
     * git (or .git/HEAD alone, with no git on the server). The repo URL is the checkout's origin when it's one
     * computers can fetch from over HTTPS, else the jar's.
     */
    public static Rom fromCheckout(Path dir, Rom jar) throws IOException {
        Map<String, byte[]> all = new TreeMap<>();
        for (var d : DIRS) {
            var sub = dir.resolve(d);
            if (!Files.isDirectory(sub)) continue;
            try (Stream<Path> walk = Files.walk(sub)) {
                for (var p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    all.put(dir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                }
            }
        }
        var commit = git(dir, "rev-parse", "HEAD");
        if (commit == null) commit = headFromFiles(dir);
        long date = parseLong(git(dir, "log", "-1", "--format=%ct", "HEAD"));
        var branch = git(dir, "rev-parse", "--abbrev-ref", "HEAD");
        var url = httpsUrl(git(dir, "remote", "get-url", "origin"));
        return new Rom("the checkout " + dir.toAbsolutePath().normalize(), commit == null ? "unknown" : commit, date,
            url != null ? url : jar.url, branch != null && !branch.equals("HEAD") ? branch : jar.branch, all);
    }

    /** The .manifest: JSON, as bin/git.lua's --lower reads it. */
    public byte[] manifest() {
        var b = new StringBuilder("{\"url\":").append(json(url)).append(",\"branch\":").append(json(branch))
            .append(",\"commit\":").append(json(commit)).append(",\"date\":").append(date)
            .append(",\"source\":").append(json(source)).append(",\"files\":{");
        var first = true;
        for (var e : files.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append(json(e.getKey())).append(':').append(json(blobSha(e.getValue())));
        }
        return b.append("}}").toString().getBytes(StandardCharsets.UTF_8);
    }

    /** git's name for a blob with these bytes, as the GitHub and Forgejo trees give it. */
    public static String blobSha(byte[] data) {
        try {
            var sha = MessageDigest.getInstance("SHA-1");
            sha.update(("blob " + data.length + "\0").getBytes(StandardCharsets.US_ASCII));
            var hex = new StringBuilder();
            for (var x : sha.digest(data)) hex.append(String.format("%02x", x));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A path a ResourceLocation can hold: a-z 0-9 / . _ - */
    static boolean servable(String path) {
        return !path.isEmpty() && path.chars().allMatch(c -> c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
            || c == '/' || c == '.' || c == '_' || c == '-');
    }

    /** An origin as an https URL git.lua can use, or null. */
    static String httpsUrl(String origin) {
        if (origin == null) return null;
        var u = origin.trim();
        if (u.startsWith("git@github.com:")) u = "https://github.com/" + u.substring("git@github.com:".length());
        if (!u.startsWith("https://")) return null;
        if (u.endsWith(".git")) u = u.substring(0, u.length() - 4);
        return u;
    }

    private static InputStream open(ClassLoader jar, String name) throws IOException {
        var in = jar.getResourceAsStream(name);
        if (in == null) throw new IOException("no " + name + " in the jar");
        return in;
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** git's output (trimmed), or null if it fails or isn't there. */
    private static String git(Path dir, String... args) {
        var cmd = new ArrayList<String>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        try {
            var p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            p.getOutputStream().close();
            var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) return null;
            return out.isEmpty() ? null : out;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** The commit .git/HEAD names, without git. */
    private static String headFromFiles(Path dir) throws IOException {
        var head = dir.resolve(".git/HEAD");
        if (!Files.isRegularFile(head)) return null;
        var text = Files.readString(head).trim();
        if (!text.startsWith("ref: ")) return text;
        var ref = text.substring(5);
        var loose = dir.resolve(".git").resolve(ref);
        if (Files.isRegularFile(loose)) return Files.readString(loose).trim();
        var packed = dir.resolve(".git/packed-refs");
        if (Files.isRegularFile(packed)) {
            for (var line : Files.readAllLines(packed)) {
                if (line.endsWith(" " + ref)) return line.substring(0, line.indexOf(' '));
            }
        }
        return null;
    }

    private static String json(String s) {
        if (s == null) return "null";
        var b = new StringBuilder("\"");
        for (var c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
