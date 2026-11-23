import SwiftUI

extension Text {
    /// Text respelled for the user's English variant (US, Canadian or
    /// British/Australian/NZ). Use for any copy containing a word in
    /// `RegionalSpelling`, including strings from `Analysis/`.
    init(regional text: String) {
        self.init(verbatim: RegionalSpelling.apply(text))
    }
}
