package ccnix.rom;

import org.junit.jupiter.api.Test;
import org.squiddev.cobalt.LuaState;
import org.squiddev.cobalt.LuaThread;
import org.squiddev.cobalt.compiler.LoadState;
import org.squiddev.cobalt.lib.CoreLibraries;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The ROM's lib/etlua.lua (etlua, by Leaf Corcoran: ccnix's httpd templates) in Cobalt, CC's Lua VM, with the
 * libraries CC gives a computer (CoreLibraries.debugGlobals, as CobaltLuaMachine does): etlua has no setfenv there and
 * goes through debug.getupvalue and debug.upvaluejoin instead, which the sims' Lua 5.4 can't speak for.
 */
class EtluaTest {
    @Test
    void etluaRendersInCobalt() throws Exception {
        var state = new LuaState();
        CoreLibraries.debugGlobals(state);
        var lib = Path.of(System.getProperty("ccnix.net")).resolveSibling("etlua.lua");
        try (var in = Files.newInputStream(lib)) {
            state.globals().rawset("etlua", LuaThread.runMain(state, LoadState.load(state, in, "@lib/etlua.lua", state.globals())).first());
        }
        var code = """
            local t = assert(etlua.compile('<p><%= x %></p><% for i = 1, n do %>[<%- i %>]<% end %><%= y or "-" %>'))
            return t({ x = "<b>&", n = 3 }) .. "|" .. t({ x = "again", n = 0, y = 'q"' })
            """;
        var fn = LoadState.load(state, new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8)), "=test", state.globals());
        assertEquals("<p>&lt;b&gt;&amp;</p>[1][2][3]-|<p>again</p>q&quot;", LuaThread.runMain(state, fn).first().toString());
    }
}
