# Library first-30 diagnostic build

Temporary diagnostic branch: `test/library-first-30`.

The Library grid keeps the first 30 `[data-play-title]` cards visible and applies `display: none` to cards 31 onward. The existing `content-visibility: auto` rule is retained so this tests the additional effect of limiting visible cards against the current main baseline.

This is a test-only branch and must not be merged into `main`. After installing the build, stop downloads and run each route at least three times: Home → Library, Settings → Library, Downloads → Library. Record `total`, `raf1`, and `raf2` for each run, plus whether all 30 visible cards and their thumbnails appear correctly.
