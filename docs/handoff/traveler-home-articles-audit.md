# Home: slider artikel, 8 November 2026

Figma SSOT: [section 9:301](https://www.figma.com/design/ifQcOxbjGTXw9aNH2JGl9g/Floopel-Mobile?node-id=9-301), rail 1107:14432, editorial component 791:10836. Reuse the mapped editorial composition in common `core:designsystem`: 304dp card, 12dp gap, full-width 16:9 media (304×171dp), 8dp header/text gutters, 12dp radius. At a 400dp viewport with 16dp screen gutters, the next card peeks by 52dp. Section action uses the existing catalog heading component (13sp, ≥48dp target).

Home requests ten entries and displays at most ten unique IDs in API order. No autoplay or placeholder articles. Titles and snippets use two lines; metadata wraps. Images remain API-owned. Missing counts show `—`; actual zero remains zero. Tapping a card uses the existing reader. “Lihat semua” uses the existing paginated archive and can display more than ten entries.

The branch's existing Home feed is global. `PersonalProfile` has no location field and `TravelerCatalog.stories` has no location parameter. This change preserves that contract and does **not** claim to implement a location-aware profile-to-global fallback. Nationality and device coordinates are not substituted for a profile location. The Figma sample heading “Cerita dari Magelang” is therefore not hardcoded into runtime.

## Executed verification

- Build/lint both Android applications: PASS for the new core component. Final Traveler build, host suite, test APK assembly and lint: PASS after the heading adjustment.
- `TravelerHomeArticleDesignTest`: **12 native tests PASS**, emulator 5580, 13.322s. Frozen local source/APK hashes remained unchanged during the run.
- Cases: 0/1/2/10/>10 responses, duplicate IDs, API order and both rail ends, exact width/media/gap/peek, actual image loading, unknown comments, reader/Back, archive item 12, deferred loading, failure/retry, profile failure with independent global content, ID/EN, long text and missing media at 130%/200%.
- Screenshot review: rail layout and text scaling inspected. The pre-existing main bottom navigation wraps at 200% font; it is outside this slider change.
- iOS compilation/native UI and TalkBack: **NOT_RUN**; Windows has no Mac/Xcode and no assistive-technology session was performed.

Evidence: [manifest](traveler-android-audit/home-articles-20261108/manifest.json), [native output](traveler-android-audit/home-articles-20261108/native.log), [two cards](traveler-android-audit/home-articles-20261108/home-articles-two.png), [200% text](traveler-android-audit/home-articles-20261108/home-articles-font200.png). Screenshots contain synthetic test identities and data only. They are not live API or whole-application acceptance proof. Local raw source fingerprints are execution provenance; the unrelated dirty Android `styles.xml` was compiled locally and excluded from the commit.
