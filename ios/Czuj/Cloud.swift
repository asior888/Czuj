import Foundation

// Wspólna baza (Firebase REST). Wysyłamy TYLKO typ trackera, dzień (UTC) i kratkę ok. 2 km.
// Czytamy TYLKO mapę zbiorczą. Reguły: firebase/firestore.rules.
final class Cloud {
    static let shared = Cloud()
    // Wartości z konsoli Firebase (publiczne z założenia; dostęp chronią reguły). Puste = funkcja wyłączona.
    static let project = ""
    static let apiKey = ""
    static let grid = 0.02

    static var configured: Bool { !project.isEmpty && !apiKey.isEmpty }
    private var docs: String { "https://firestore.googleapis.com/v1/projects/\(Cloud.project)/databases/(default)/documents" }

    struct HttpError: LocalizedError {
        let code: Int
        let body: String
        var errorDescription: String? { "HTTP \(code): \(body.prefix(160))" }
    }

    struct Nearby { let total, airtag, tile, smarttag: Int; let from, to: String }

    var share: Bool {
        get { UserDefaults.standard.bool(forKey: "share") }   // domyślnie false
        set { UserDefaults.standard.set(newValue, forKey: "share") }
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

    // ---------- logowanie anonimowe ----------

    private var idToken = ""
    private var uid = ""
    private var until = Date.distantPast

    private func auth() async throws -> (String, String) {
        if !idToken.isEmpty && Date() < until { return (idToken, uid) }
        var j: [String: Any]?
        let refresh = Keychain.get("cloud_refresh")
        if !refresh.isEmpty {
            do {
                let r = try await http("https://securetoken.googleapis.com/v1/token?key=\(Cloud.apiKey)", "POST",
                                       body: "grant_type=refresh_token&refresh_token=" + (refresh.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""),
                                       type: "application/x-www-form-urlencoded")
                j = ["idToken": r["id_token"] ?? "", "refreshToken": r["refresh_token"] ?? "",
                     "localId": r["user_id"] ?? "", "expiresIn": r["expires_in"] ?? "3600"]
            } catch let e as HttpError where e.code == 400 {
                j = nil   // token unieważniony -> nowe konto; błąd sieci NIE tworzy nowego konta
            }
        }
        let k: [String: Any]
        if let j { k = j } else {
            k = try await http("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=\(Cloud.apiKey)", "POST",
                               body: "{\"returnSecureToken\":true}", type: "application/json")
        }
        idToken = k["idToken"] as? String ?? ""
        uid = k["localId"] as? String ?? ""
        let secs = Double(k["expiresIn"] as? String ?? "3600") ?? 3600
        until = Date().addingTimeInterval(secs - 300)
        Keychain.set("cloud_refresh", k["refreshToken"] as? String ?? "")
        return (idToken, uid)
    }

    /// Wysyła alarm. Najwyżej raz na typ i dzień z tego urządzenia.
    @MainActor
    func report(tag: String, lat: Double, lon: Double) async throws {
        guard Cloud.configured, share, let type = Cloud.typeOf(tag) else { return }
        let day = Cloud.today()
        let key = "\(day)_\(type)"
        var sent = UserDefaults.standard.stringArray(forKey: "sent") ?? []
        if sent.contains(key) { return }
        let (token, u) = try await auth()
        func s(_ v: String) -> [String: String] { ["stringValue": v] }
        let fields: [String: Any] = ["uid": s(u), "day": s(day), "cell": s(Cloud.cell(lat, lon)),
                                     "type": s(type), "platform": s("ios"), "v": ["integerValue": "1"]]
        let body = String(decoding: try JSONSerialization.data(withJSONObject: ["fields": fields]), as: UTF8.self)
        do {
            _ = try await http("\(docs)/reports?documentId=\(u)_\(key)", "POST", body: body, type: "application/json", bearer: token)
        } catch let e as HttpError where e.code == 409 {
            // już wysłane
        }
        sent.append(key)
        UserDefaults.standard.set(Array(sent.suffix(30)), forKey: "sent")
    }

    /// Mapa zbiorcza w promieniu ok. 3 km (3 x 3 kratki). nil = brak danych.
    func nearby(lat: Double, lon: Double) async throws -> Nearby? {
        guard Cloud.configured else { return nil }
        let doc: [String: Any]
        do {
            doc = try await http("\(docs)/stats/week?key=\(Cloud.apiKey)", "GET")
        } catch let e as HttpError where e.code == 404 {
            return nil
        }
        guard let fields = doc["fields"] as? [String: Any],
              let jsonField = fields["json"] as? [String: Any],
              let text = jsonField["stringValue"] as? String,
              let stats = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any]
        else { return nil }
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

    private func http(_ url: String, _ method: String, body: String? = nil, type: String? = nil,
                      bearer: String? = nil) async throws -> [String: Any] {
        var req = URLRequest(url: URL(string: url)!)
        req.httpMethod = method
        req.timeoutInterval = 30
        if let bearer { req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        if let body {
            req.httpBody = Data(body.utf8)
            req.setValue(type ?? "application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw HttpError(code: code, body: String(decoding: data, as: UTF8.self)) }
        if data.isEmpty { return [:] }
        return (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }
}
