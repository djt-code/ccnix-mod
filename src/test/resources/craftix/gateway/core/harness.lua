-- The tests' side of cc-turtles' lib/net: stacks on a clock the test moves.
-- What they transmit waits in H.out for the Java side (CcWorld), which
-- carries it to the other stacks, and the gateway, on the same wire.
local N = ...
local H = { now = 0, out = {}, stacks = {}, events = {} }

-- A computer: id, { iface = "a.b.c.d/len" }, { { "net/len", "gw" } }, router?
function H.stack(id, ifaces, routes, forward)
    local S = N.new({
        id = id, forward = forward,
        clock = function() return H.now end,
        queue = function(...)
            local e = table.pack(...)
            e.id = id
            H.events[#H.events + 1] = e
        end,
        transmit = function(ifc, frame) H.out[#H.out + 1] = { id, ifc.modem, ifc.channel, frame } end,
        log = function(msg) H.events[#H.events + 1] = { "log", msg, id = id } end,
    })
    for name, cidr in pairs(ifaces) do assert(S.addInterface(name, cidr)) end
    for _, r in ipairs(routes or {}) do assert(S.addRoute(r[1], r[2])) end
    H.stacks[id] = S
    return S
end

-- What was sent since the last take: { { id, modem, channel, frame }... }
function H.take()
    local o = H.out
    H.out = {}
    return o
end

function H.deliver(id, modem, channel, frame) H.stacks[id].receive(modem, channel, frame) end

function H.tick(now)
    H.now = now
    for _, S in pairs(H.stacks) do S.tick() end
end

-- The events queued so far called name (on computer id, if given).
function H.find(name, id)
    local found = {}
    for _, e in ipairs(H.events) do
        if e[1] == name and (id == nil or e.id == id) then found[#found + 1] = e end
    end
    return found
end

-- Bytes 0-255, n of them, repeating.
function H.bytes(n)
    local t = {}
    for i = 1, n do t[i] = string.char((i * 7 + 3) % 256) end
    return table.concat(t)
end

return H
