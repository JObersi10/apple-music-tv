# Apple Music TV — Design Language

The one source of truth for how the UI looks and behaves. When building anything new,
match this — do NOT hand-roll a variant. If a pattern here is missing, add it here first.

## Core principle
**One component, everywhere.** A menu, card, or row that exists in two places must be the
*same composable*, not two look-alikes. Consistency beats local cleverness. If you catch
yourself copying a `Column { … menu items … }`, stop and route through the shared component.

## Colors
| Role | Value |
|------|-------|
| Accent (Apple red) | `0xFFFA233B` |
| App background | `Color.Black` |
| Surface / menu / card sheet | `0xFF1C1C1E` |
| Surface focused (rows) | `0xFF2C2C2E` |
| Art placeholder / divider | `0xFF2A2A2A` |
| Selected-pill tint (sidebar) | `0xFF1A1A1A` |
| Primary text | `Color.White` |
| Secondary text | `0xFF888888` |
| Tertiary / empty-state text | `0xFF555555` |
| Scrim behind dialogs | `0x99000000` |

## Context menu — THE canonical long-press menu
`AmContextMenu(title, subtitle, artworkUrl, actions, onDismiss, isVideoArt)` in
`ui/components/AmComponents.kt`. Every long-press menu (Library, Album, Playlist, Artist,
Category, Browse cards) routes through it. Look:
- Real `Dialog`, full-screen `0x99000000` scrim, centered.
- 320.dp wide, `0xFF1C1C1E`, `RoundedCornerShape(16.dp)`, 8.dp padding.
- **Header**: artwork (44×44 square, or 78×44 for `isVideoArt`) + title (13sp SemiBold white)
  + subtitle (11sp `0xFF888888`). Hairline `0xFF2A2A2A` divider under it.
- **Rows** via `AmMenuItem`: 14sp, radius 8, focused `0xFF2C2C2E`, padding 16h/12v.
  `destructive=true` tints the label accent-red.
- First row auto-focuses after 450 ms; a `blocked` guard eats the OK-release that opened it.
- Build rows with `AmMenuAction(label, destructive=false) { … }` in a `buildList`.
- Standard verbs/order: Play Next · Add to Queue · Add to… · Create Station · Go to Artist · Go to Album.
No icons in menu rows (Apple TV doesn't use them here). Do not add a scrolling menu or per-screen styling.

## Cards & grids
- Album/playlist card: `AlbumCard`, square, ~150 in library grids, focusedScale ~1.04.
- Music-video card: 16:9, `RoundedCornerShape(10.dp)`, title 13sp + artist 11sp under it.
- Library grids: 4 columns (albums), 3 columns (videos); `spacedBy(18.dp)` h / `22.dp` v;
  contentPadding start 32 / end 48 / top 20 / bottom 48.
- Empty state: centered `0xFF555555` text ("No music videos").

## Rows / list items
- Sidebar item: red accent bar (2.dp) + tinted pill when selected; 13sp, Medium when selected.
- Focus: prefer scale over borders; suppress the yellow TV focus border
  (`ClickableSurfaceDefaults.border(noBorder, noBorder)`), use focused container color instead.

## Motion / focus
- Long-press → menu: 450 ms focus delay + `blocked` flag (never trigger the first row on the
  opening OK press).
- Color cross-fades ~150 ms (`tween(150)`), background blob fades ~1.5 s.
- Never `Modifier.blur()` (no-op + jank on Fire TV); tinted scrims instead. Max 4 blobs on
  the Now Playing background.

## Copy
- Caveman-clean labels: "Add to Queue", "Go to Artist". No emojis.
- "Add to…" (ellipsis) opens the playlist picker.

## Files
- Shared UI atoms: `ui/components/AmComponents.kt` (menu, cards, glass, `AmTokens`).
- Add new reusable pieces there, then reference from screens — never redefine per screen.
