import Foundation

// Reguły trackerów, te same co linux/scanner.py i Android (TrackerCore.kt).
enum TrackerCore {
    static let minSightings = 4
    static let trackSeconds: TimeInterval = 25 * 60
    static let minPlaces = 2
    static let minPerPlace = 2
    static let repeatSeconds: TimeInterval = 6 * 3600
    static let placeJaccard = 0.34
    static let maxPlaces = 80
    static let maxDevices = 500
    static let debounceSeconds: TimeInterval = 60

    /// mfr: identyfikator firmy -> dane BEZ 2 bajtów identyfikatora.
    static func classify(mfr: [Int: [UInt8]], uuids: [String]) -> String {
        if let apple = mfr[0x004C], apple.count >= 2, apple[0] == 0x12, apple[1] == 0x19 {
            return "AirTag / Find My"
        }
        for raw in uuids {
            let u = normalize(raw)
            if u.hasPrefix("0000feed") || u.hasPrefix("0000feec") { return "Tile" }
            if u.hasPrefix("0000fd5a") { return "SmartTag (Samsung)" }
        }
        return ""
    }

    /// iOS podaje krótkie UUID ("FEED"); zamieniamy na pełną postać.
    static func normalize(_ raw: String) -> String {
        let u = raw.lowercased()
        return u.count == 4 ? "0000\(u)-0000-1000-8000-00805f9b34fb" : u
    }

    final class Rec {
        var tag: String
        var n = 0
        let first: TimeInterval
        var last: TimeInterval
        var places: [Int: Int] = [:]
        init(tag: String, now: TimeInterval) { self.tag = tag; first = now; last = now }
    }

    final class Watch {
        var places: [Set<String>] = []
        var seen: [String: Rec] = [:]
        var alerted: [String: TimeInterval] = [:]

        func placeOf(_ top: Set<String>) -> Int {
            if top.isEmpty { return -1 }
            for (i, p) in places.enumerated() {
                let union = top.union(p).count
                if union > 0 && Double(top.intersection(p).count) / Double(union) >= placeJaccard { return i }
            }
            if places.count >= maxPlaces { return -1 }
            places.append(top)
            return places.count - 1
        }

        /// Jedna obserwacja trackera. Zwraca tekst alarmu albo nil.
        func sighting(addr: String, tag: String, top: Set<String>, now: TimeInterval) -> String? {
            if let prev = seen[addr], now - prev.last < debounceSeconds { return nil }
            let place = placeOf(top)
            let rec = seen[addr] ?? Rec(tag: tag, now: now)
            seen[addr] = rec
            rec.n += 1
            rec.last = now
            rec.places[place, default: 0] += 1
            prune()
            let span = rec.last - rec.first
            let multi = rec.places.filter { $0.key >= 0 && $0.value >= minPerPlace }.count
            if rec.n < minSightings || span < trackSeconds || multi < minPlaces { return nil }
            if let last = alerted[addr], now - last < repeatSeconds { return nil }
            alerted[addr] = now
            return "Uwaga: \(rec.tag) jest przy tobie od \(Int(span / 60)) minut, w \(multi) różnych miejscach. " +
                "Jeśli to nie twoje, sprawdź torbę, kurtkę i pojazd."
        }

        func nearby(now: TimeInterval, window: TimeInterval = 120) -> Int {
            seen.values.filter { now - $0.last <= window }.count
        }

        private func prune() {
            guard seen.count > maxDevices else { return }
            let oldest = seen.sorted { $0.value.last < $1.value.last }.prefix(seen.count - maxDevices)
            for (k, _) in oldest { seen.removeValue(forKey: k) }
        }
    }
}
