// Test reguł bez Xcode: swiftc Czuj/TrackerCore.swift Tests/main.swift -o t && ./t
import Foundation

var failures = 0
func check(_ ok: Bool, _ name: String) {
    print((ok ? "OK   " : "BŁĄD ") + name)
    if !ok { failures += 1 }
}

check(TrackerCore.classify(mfr: [0x004C: [0x12, 0x19, 0]], uuids: []) == "AirTag / Find My", "AirTag")
check(TrackerCore.classify(mfr: [0x004C: [0x10, 0x05]], uuids: []) == "", "inne urządzenie Apple")
check(TrackerCore.classify(mfr: [:], uuids: ["FEED"]) == "Tile", "Tile (krótkie UUID)")
check(TrackerCore.classify(mfr: [:], uuids: ["0000fd5a-0000-1000-8000-00805f9b34fb"]) == "SmartTag (Samsung)", "SmartTag")

let home: Set<String> = ["a", "b", "c", "d", "e", "f"]
let away: Set<String> = ["u", "v", "w", "x", "y", "z"]

var w = TrackerCore.Watch()
var alarms = 0
var t: TimeInterval = 0
for p in [home, home, away, away, away] {
    if w.sighting(addr: "AA", tag: "AirTag / Find My", top: p, now: t) != nil { alarms += 1 }
    t += 8 * 60
}
check(alarms == 1, "alarm, gdy tracker jedzie z nami")

w = TrackerCore.Watch(); alarms = 0; t = 0
for _ in 0..<8 {
    if w.sighting(addr: "CC", tag: "Tile", top: home, now: t) != nil { alarms += 1 }
    t += 8 * 60
}
check(alarms == 0, "brak alarmu w jednym miejscu")

w = TrackerCore.Watch(); alarms = 0; t = 0
for p in [home, home, away, away, away, away, away, away] {
    if w.sighting(addr: "EE", tag: "Tile", top: p, now: t) != nil { alarms += 1 }
    t += 8 * 60
}
check(alarms == 1, "najwyżej raz na 6 h")

w = TrackerCore.Watch()
for i in 0..<10 { _ = w.sighting(addr: "FF", tag: "Tile", top: home, now: TimeInterval(i)) }
check(w.seen["FF"]?.n == 1, "jedna obserwacja na minutę")

exit(failures == 0 ? 0 : 1)
