import Foundation

// Wspólna mapa: serwer Cloudflare (server/worker.js), bez kont i logowania.
// Wysyłamy TYLKO: losowy numer instalacji (do liczenia różnych urządzeń), typ trackera, dzień (UTC), kratkę ok. 2 km.
// Czytamy TYLKO mapę zbiorczą (kratki z >= 3 urządzeń, opóźnienie 2 dni).
final class Cloud {
    static let shared = Cloud()
    /// Adres serwera (ten sam co "api" w wersja.json). Pusty = funkcja wyłączona.
    static let api = ""
    static let grid = 0.02

    static var configured: Bool { !api.isEmpty }

    struct HttpError: LocalizedError {
        let code: Int
        var errorDescription: String? { "serwer \(code)" }
    }

    struct Nearby { let total, airtag, tile, smarttag: Int; let from, to: String }

    var share: Bool {
        get { UserDefaults.standard.bool(forKey: "share") }   // domyślnie false
        set { UserDefaults.standard.set(newValue, forKey: "share") }
    }

    /// Losowy numer instalacji (16 bajtów hex), tylko do liczenia różnych telefonów w kratce.
    var installId: String {
        if let id = UserDefaults.standard.string(forKey: "install_id"), id.count == 32 { return id }
        var b = [UInt8](repeating: 0, count: 16)
        for i in 0..<16 { b[i] = UInt8.random(in: 0...255) }
        let id = b.map { String(format: "%02x", $0) }.joined()
        UserDefaults.standard.set(id, forKey: "install_id")
        return id
    }

    static func typeOf(_ tag: String) -> String? {
        if tag.hasPrefix("AirTag") { return "airtag" }
        if tag.hasPrefix("Tile") { return "tile" }
        if tag.hasPrefix("SmartTag") { return "smarttag" }
        return nil
    }

    /// Zaokrąglenie jak Math.round w Kotlinie (floor(x + 0.5)), żeby kratki były identyczne na każdej platformie.
    static func cell(_ lat: Double, _ lon: Double) -> String {
        func r(_ v: Double) -> Double { (v / grid + 0.5).rounded(.down) * grid }
        let s = String(format: "%.2f:%.2f", locale: Locale(identifier: "en_US_POSIX"), r(lat), r(lon))
        return s.replacingOccurrences(of: "-0.00", with: "0.00")
    }

    private static func today() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: Date())
    }

    /// Wysyła alarm. Najwyżej raz na typ i dzień z tego urządzenia.
    @MainActor
    func report(tag: String, lat: Double, lon: Double) async throws {
        guard Cloud.configured, share, let type = Cloud.typeOf(tag) else { return }
        let day = Cloud.today()
        let key = "\(day)_\(type)"
        var sent = UserDefaults.standard.stringArray(forKey: "sent") ?? []
        if sent.contains(key) { return }
        let body: [String: String] = ["dev": installId, "day": day, "type": type,
                                      "cell": Cloud.cell(lat, lon), "platform": "ios"]
        _ = try await http("\(Cloud.api)/v1/report", "POST", body: try JSONSerialization.data(withJSONObject: body))
        sent.append(key)
        UserDefaults.standard.set(Array(sent.suffix(30)), forKey: "sent")
    }

    /// Mapa zbiorcza w promieniu ok. 3 km (3 x 3 kratki). nil = brak danych.
    func nearby(lat: Double, lon: Double) async throws -> Nearby? {
        guard Cloud.configured else { return nil }
        let stats = try await http("\(Cloud.api)/v1/stats", "GET")
        let cells = stats["cells"] as? [String: [String: Int]] ?? [:]
        var t = 0, a = 0, ti = 0, sm = 0
        for dy in -1...1 {
            for dx in -1...1 {
                guard let c = cells[Cloud.cell(lat + Double(dy) * Cloud.grid, lon + Double(dx) * Cloud.grid)] else { continue }
                t += c["total"] ?? 0; a += c["airtag"] ?? 0; ti += c["tile"] ?? 0; sm += c["smarttag"] ?? 0
            }
        }
        return Nearby(total: t, airtag: a, tile: ti, smarttag: sm,
                      from: stats["from"] as? String ?? "", to: stats["to"] as? String ?? "")
    }

    private func http(_ url: String, _ method: String, body: Data? = nil) async throws -> [String: Any] {
        var req = URLRequest(url: URL(string: url)!)
        req.httpMethod = method
        req.timeoutInterval = 20
        if let body {
            req.httpBody = body
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw HttpError(code: code) }
        if data.isEmpty { return [:] }
        return (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }
}
