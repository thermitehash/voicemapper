import Foundation

/// Regional English for the launch markets (US, Canada, UK, Australia,
/// New Zealand, and Iceland, which gets British spelling). The app's copy is written once in British/Commonwealth
/// spelling, which UK, Australian and NZ users see as is. US and Canadian users
/// get a word-for-word respelling at display time. Every market also gets its
/// own name for the voice professional and its own directory link.
///
/// Done at display time rather than with a String Catalog because much of the
/// copy lives in `Analysis/` (exercise steps, evidence notes, stage
/// requirements), is built with interpolation, and must stay verbatim for the
/// Android ParityTest. The variants differ only in spelling and a few words
/// (mum/mom), so a word list covers them. See `docs/LOCALIZATION.md`.
enum RegionalSpelling {
    enum Variant: String, CaseIterable, Sendable {
        /// UK (and the fallback): the source spelling.
        case british
        /// Australia: British spelling.
        case australian
        /// New Zealand: British spelling.
        case newZealand
        /// Canada: -ize, "mom", otherwise British (colour, centre, practise).
        case canadian
        /// United States.
        case american
    }

    /// Picks the variant from the user's first preferred language, falling back
    /// to the device region (e.g. "English" with region Canada).
    static func variant(preferredLanguages: [String] = Locale.preferredLanguages,
                        region: String? = Locale.current.region?.identifier) -> Variant {
        let first = preferredLanguages.first.map { Locale(identifier: $0) }
        let code = first?.region?.identifier ?? region
        switch code {
        case "US", "PR", "UM", "VI", "AS", "GU", "MP": return .american
        case "CA": return .canadian
        case "AU": return .australian
        case "NZ": return .newZealand
        default: return .british
        }
    }

    /// What each market calls the professional who does voice therapy. The
    /// source text uses the US/Canadian "speech-language pathologist".
    static func professional(_ variant: Variant) -> String {
        switch variant {
        case .american, .canadian: "speech-language pathologist"
        case .british: "speech and language therapist"
        case .australian: "speech pathologist"
        case .newZealand: "speech-language therapist"
        }
    }

    /// The national professional body's public directory, by the country the
    /// phone is set to (where the user lives, not which spelling they read).
    /// Links checked Nov 2026; Canada's opens SAC, whose finder is on the page.
    static func directory(region: String? = Locale.current.region?.identifier) -> (name: String, url: URL) {
        switch region {
        case "CA": ("Speech-Language & Audiology Canada", URL(string: "https://www.sac-oac.ca/find-speech-language-pathologist-or-audiologist-service")!)
        case "GB", "IM", "JE", "GG": ("ASLTIP (independent speech and language therapists)", URL(string: "https://asltip.com/find-a-speech-therapist/")!)
        case "AU": ("Speech Pathology Australia", URL(string: "https://speechpathologyaustralia.org.au/find-a-speech-pathologist")!)
        case "NZ": ("New Zealand Speech-language Therapists' Association", URL(string: "https://speechtherapy.org.nz/find-a-therapist/")!)
        // Iceland (English-only listing): the association's public member list, in Icelandic.
        case "IS": ("Félag talmeinafræðinga á Íslandi (member list, in Icelandic)", URL(string: "https://www.talmein.is/felagatal/")!)
        default: ("ASHA ProFind", URL(string: "https://find.asha.org/pro/")!)
        }
    }

    /// Lowercase British → Canadian pairs. Capitalised forms are derived.
    private static let canadianPairs: [(String, String)] = [
        ("analyse", "analyze"), ("analysed", "analyzed"),
        ("apologise", "apologize"),
        ("emphasise", "emphasize"),
        ("minimise", "minimize"),
        ("mum", "mom"), ("mums", "moms"),
        ("organise", "organize"), ("organised", "organized"),
        ("personalise", "personalize"), ("personalised", "personalized"),
        ("realise", "realize"), ("realised", "realized"),
        ("recognise", "recognize"), ("recognised", "recognized"),
        ("summarise", "summarize"),
    ]

    /// Lowercase British → US pairs (on top of the Canadian ones).
    private static let americanPairs: [(String, String)] = canadianPairs + [
        ("behaviour", "behavior"),
        ("centre", "center"), ("centred", "centered"), ("centres", "centers"),
        ("colour", "color"), ("colours", "colors"), ("coloured", "colored"),
        ("favourite", "favorite"),
        ("grey", "gray"),
        ("metre", "meter"), ("metres", "meters"),
        ("practise", "practice"), ("practised", "practiced"), ("practising", "practicing"),
        ("programme", "program"),
    ]

    private static func expanded(_ pairs: [(String, String)]) -> [(NSRegularExpression, String)] {
        pairs.flatMap { from, to in
            [(from, to), (from.prefix(1).uppercased() + from.dropFirst(), to.prefix(1).uppercased() + to.dropFirst())]
        }.map { from, to in
            (try! NSRegularExpression(pattern: "\\b\(from)\\b"), to)
        }
    }

    nonisolated(unsafe) private static let canadianRules = expanded(canadianPairs)
    nonisolated(unsafe) private static let americanRules = expanded(americanPairs)
    nonisolated(unsafe) private static let cache = NSCache<NSString, NSString>()

    /// `text` respelled for `variant`.
    static func apply(_ text: String, variant: Variant = variant()) -> String {
        let rules: [(NSRegularExpression, String)]
        switch variant {
        case .british, .australian, .newZealand: rules = []
        case .canadian: rules = canadianRules
        case .american: rules = americanRules
        }
        let key = "\(variant.rawValue)|\(text)" as NSString
        if let hit = cache.object(forKey: key) { return hit as String }
        var out = text
        let pro = professional(variant)
        if pro != "speech-language pathologist" {
            out = out.replacingOccurrences(of: "speech-language pathologist", with: pro)
                .replacingOccurrences(of: "Speech-language pathologist", with: pro.prefix(1).uppercased() + pro.dropFirst())
        }
        for (regex, to) in rules {
            out = regex.stringByReplacingMatches(in: out, range: NSRange(out.startIndex..., in: out), withTemplate: to)
        }
        cache.setObject(out as NSString, forKey: key)
        return out
    }
}
