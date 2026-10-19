# Design

The app looks like a workspace: black ink on white paper, and its inverse. This
document records *why* it looks that way and what the rules are, so a change can
be judged against them instead of against taste.

---

## 1. One rule: no hue

There is no colour anywhere — not for accents, not for "success", not for errors.
A screen here can simultaneously hold a long transcript, a syntax-marked code
block, an added/removed diff, a context gauge and a token-speed readout. Colour
would be a fourth visual channel competing with the three that already carry
meaning.

So state is encoded with the tools a monochrome page actually has:

| What needs showing | How it is shown |
| --- | --- |
| Who wrote this message | **Polarity.** You = solid ink bubble, right-aligned. The model = bare text on paper, left-aligned. |
| Primary vs. secondary action | **Fill.** Destructive and primary actions are solid ink; safe actions are outlines. |
| Importance of a label | **Weight**, then size. The type scale is deliberately narrow (see §3). |
| Added vs. removed lines | **Tint + gutter mark.** A faint paper step plus `+` / `−` in the gutter — never green and red. |
| Something is happening | **Motion.** A sweeping 2 dp rule, a blinking caret (§5). |
| On / off | **Fill and outline** on chips and switches, plus a text label. Colour is never the only signal. |
| Severity | **Icon + wording.** A warning triangle and the engine's own sentence, not a red banner. |

This is also why Material You dynamic colour is *not* used: a wallpaper-derived
palette would reintroduce hue and undo the whole premise. `OfflineGgufTheme`
installs a fixed scheme.

## 2. Palette

`ui/theme/Color.kt` defines one object, `Ink`, and two schemes built from it.

```
paper (light)   FFFFFF  FCFCFC  F7F7F7  F1F1F2  EAEAEC
ink   (dark)    0B0B0C  17171A  1D1D21  26262B  33333A
text  (light)   0B0B0C  4C4C53  63636B      primary → secondary → muted
text  (dark)    F6F6F7  B9B9C0  9A9AA2
rules (light)   8E8E96 strong · DCDCE0 separator
rules (dark)    72727B strong · 2E2E34 separator
diff  (light)   F2F2F3 added · E6E6E8 removed · 55555C gutter
diff  (dark)    1B1B1F added · 26262B removed · 9A9AA2 gutter
```

The text ramp has **three steps, not four**, and each is measured rather than
eyeballed: 19.67 / 8.52 / 5.95 against paper, 18.22 / 10.08 / 7.04 against ink.
Every one clears WCAG AA (4.5:1), so `muted` is the floor — there is no "faint"
role, because a monochrome design has nowhere to hide an unreadable label. The
`outline` role (`RuleStrong*`, 3.25:1 / 4.13:1) is what interactive boundaries
use — field, chip, switch and button outlines — clearing the 3:1 non-text
minimum, while `outlineVariant` stays quiet for the 1 dp separators, which are
decorative (spacing does the real dividing).

`LightInkScheme` and `DarkInkScheme` map that ramp onto every Material 3 role,
including the `surfaceContainer*` steps, so a component that asks for
"surface, one step raised" gets the right paper without naming a hex value.
`error` is *not* red — it is ink, because in this palette an error is a heavy
statement, not a coloured one.

`ThemeMode` offers `SYSTEM` / `LIGHT` ("Paper") / `DARK` ("Ink"). Only the
polarity flips; both are monochrome.

One helper keeps components honest: `diffPalette(dark, highContrast)` resolves the
diff tints and gutter glyphs for the active scheme, so `CodeBlock` never inspects
light/dark itself.

### High contrast

The "High contrast" preference calls `ColorScheme.highContrast(dark)`, which
promotes every muted text role (`onSurfaceVariant`, `tertiary`, `secondary`) to
full-strength ink, collapses all five tinted paper steps onto plain paper, and
turns hairlines into rules. Diff tints are pushed further apart at the same time.
Nothing on screen then relies on a subtle grey to be legible — and it costs no
extra palette, because monochrome already has both extremes.

## 3. Typography

`ui/theme/Type.kt`. Two families only: the system sans (`FontFamily.Default`) and
`FontFamily.Monospace` for code, tensor tables, metadata keys and gauges. No font
files are bundled, which keeps the APK small and the text crisp at every density.

The scale is quiet on purpose — 30 / 25 / 21 / 19 / 16 / 14 sp for display down
to body, 12 / 11 sp for labels. Hierarchy comes from weight and space rather than
from shouting, because most of what is on screen is reading matter.

Four named styles exist outside the Material scale:

| Style | Use |
| --- | --- |
| `ChatProse` | 15.5 sp with a relaxed line height — the transcript body |
| `CodeText` | 12.5 sp monospace — code blocks and diffs |
| `CodeGutterText` | 11 sp monospace — line numbers, `+`/`−` marks |
| `MetaKeyText` | 12 sp monospace — GGUF metadata keys |

The user's **font scale** preference is applied by overriding `LocalDensity` in
`MainActivity`, which is what `sp` resolves against. One line scales every piece
of text in the app; the type scale is not rebuilt per size.

## 4. Space and shape

`Spacing` is a 4 dp grid (`xxs` 2 … `xxxl` 48) plus the handful of named rhythms
that actually appear in layouts: `gutter` 20 (screen margin), `messageGap` 18,
`bubble` 20, `card` 12, `chip` 8, `field` 10, `hairline` 1, `rule` 2, and
`target` 48 (the minimum touch target). Screens take padding from here rather
than hard-coding numbers, which is what makes seven screens look like one design.

`InkShapes` is 2 / 4 / 8 / 12 dp plus the 20 dp bubble. Nothing else is rounded
and nothing is fully circular — pill shapes would fight the rectangular,
paper-like layout. The message bubble is the one asymmetric shape in the app:
`RoundedCornerShape(bubble, bubble, xs, bubble)`, with the small corner pointing
back at whoever sent it.

## 5. Motion

`ui/theme/MotionSpec` is the single source of timings, exposed as
`OfflineGgufTheme.motion`.

| Token | ms | Used for |
| --- | --- | --- |
| `fast` | 150 | press feedback, chip toggles, height easing, count reveals |
| `standard` | 220 | fades, slides, expand/collapse, navigation bar cross-fade |
| `slow` | 320 | message entry, navigation pushes, sheet settle |
| `caretBlink` | 760 | streaming caret (0 under reduced motion) |

Four cubic-bezier curves: `easeStandard`, `easeEmphasized` (long travel —
navigation pushes), `easeDecelerate` (entering), `easeAccelerate` (exiting).
Nothing bounces; elements arrive and leave.

The governing rule: **nothing the user is waiting on exceeds ~220 ms, and nothing
that carries meaning is shorter than ~320 ms.** A message appearing is meaning;
a chip changing state is not.

### Reduce motion

`MotionSpec.forPreference(reduceMotion)` returns a spec whose durations are all
zero, so a call site written as `tween(motion.fast)` becomes a clean cut without
knowing the setting exists. `reduced` is exposed for the one case where an
animation should not be *started* at all: `BlinkingCaret` skips
`rememberInfiniteTransition` entirely and stays lit.

The preference also reaches the places that are not driven by the spec:
`ChatScreen` swaps `animateScrollToItem` for `scrollToItem`, and `ChatViewModel`
narrows the token-coalescing window from 256 ms to 120 ms so text appears in
larger, less frequent steps.

### Where animation is used

| Place | Animation |
| --- | --- |
| Newest transcript entry | fade + rise (`it / 6`) + `AnimatedVisibility`, `slow`, decelerating |
| Assistant message body | `animateContentSize(fast)` — eases taller as tokens land and when the reasoning block expands |
| Like button | one `Animatable` scale bump to 1.22× and back when its state changes; the count slides open with `expandHorizontally` the first time it becomes non-zero |
| Streaming rule | `LinearProgressIndicator` sweeping in a 2 dp band, faded in/out at `fast` |
| Caret | infinite 1.0 → 0.1 alpha, reversing, at `caretBlink` |
| Reasoning block | `expandVertically` + fade |
| Code block | `animateContentSize(standard)` for "Show all N lines" / "Collapse" and for diff reveal |
| Navigation | fade at `standard` + horizontal slide at `slow`, emphasized in / accelerated out |
| Scroll-to-latest pill | fade + slide at the bottom edge of the transcript |

Entry animation is applied **only to the newest message** — re-animating every row
each time the list scrolls would be noise, and would read as a rendering bug.

## 6. Code blocks and diffs

`CodeBlock` handles three inputs and renders them the same way:

1. **A unified diff written by the model** (`language == "diff"`/`"patch"`, or
   `UnifiedDiff.looksLikeDiff(content)` sniffing `---`/`+++`/`@@` headers) — parsed
   into hunks so added and removed lines can be marked even though the app did not
   compute them. This is what the *Patch mode* skill produces.
2. **A change against the previous answer** — `LineDiffer.diff(previous, content)`
   when the message was regenerated, so you can see what the second attempt did
   differently.
3. **Plain code** — no marks, just gutter line numbers.

Changed rows get a faint tint, a `+` / `−` / space gutter mark (the two glyphs
come from `DiffPalette`, so the legend and the gutter cannot drift apart), and
old/new line numbers side by side; skipped ranges collapse into a
`⋯ N unchanged` row. `DiffLegend`
explains the encoding above a patch, because monochrome tints are not self-evident
the way green and red are.

Blocks longer than 80 lines collapse behind "Show all N lines" so a 900-line dump
cannot stall a scroll. Horizontal scrolling is per-block, never per-screen.

## 7. Components

Everything reusable lives in `ui/components/`, and every one of them reads its
colours from `MaterialTheme.colorScheme` and its numbers from
`OfflineGgufTheme.spacing` — no component names a hex value or a raw `dp` rhythm.

```
Common.kt       SectionTitle  Hairline  InkChip  KeyValueRow  StatChip
                InkIconButton  InkTopBar  EmptyState  InkButton
                ConfirmDialog  InkColumn  StatRow  Swatch  LabeledRow
CodeBlock.kt    CodeBlock  DiffLegend
MarkdownText.kt MarkdownText  TableBlock  plainText  codeSnippets
Gauges.kt       StreamingRule  BlinkingCaret  ContextGauge  SpeedRow
                FlagChip  ThinkingBlock  VerdictBadge  FactRow
MessageRow.kt   MessageRow  LikeButton
InputBar.kt     InputBar
```

Markdown is rendered by hand (`MarkdownText`) rather than pulled from a library:
headings, emphasis, inline code, fenced blocks, lists, block quotes and tables.
The quote rule is a 2 dp `Box` with `fillMaxHeight` inside a
`Row(Modifier.height(IntrinsicSize.Min))`, which is what makes it span a
multi-line quote instead of only its first line.

## 8. Icons

46 hand-written stroke vectors in `res/drawable/ic_*.xml`: 24 dp viewport, a
uniform 1.7 dp stroke, round caps and joins, no fills — plus two launcher layers
(`ic_launcher_background`, `ic_launcher_foreground`). No icon font and no icon
dependency, which keeps them sharp at every density and consistent with the
hairline rules the rest of the UI draws.

The launcher icon is adaptive on API 26+ (`mipmap-anydpi-v26`) and the same two
layers composited as a `layer-list` in plain `mipmap-anydpi` below that, so
minSdk 24 is covered without shipping a single PNG.

## 9. Accessibility

- 48 dp minimum touch target (`spacing.target`); icon buttons are 40 dp inside a
  padded row so the effective target holds.
- Every icon-only control carries a `contentDescription` that states the action
  and, where relevant, the current state ("Remove like" vs. "Like this answer").
- Contrast, measured: 19.67:1 / 8.52:1 / 5.95:1 for the three light-mode text
  roles on paper, and 18.22:1 / 10.08:1 / 7.04:1 for the dark-mode ramp on ink.
  The quietest text in the app is AA; interactive borders clear 3:1; the
  high-contrast mode removes the greys entirely.
- State is never colour-only (there is no colour), never motion-only, and never
  shape-only — every toggle also has a text label.
- Font scale, reduce motion and high contrast are user preferences, not
  system-only: all three are in Settings and all three are honoured.
- `android:screenReader`-friendly ordering: each transcript entry reads role,
  content, then actions.
