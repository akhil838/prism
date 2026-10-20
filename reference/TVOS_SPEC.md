# tvOS reference spec (notes from Apple's Human Interface Guidelines, read 2026-10-18)

Facts and numbers noted from developer.apple.com/design/human-interface-guidelines; the pages themselves are Apple's and are not kept in this repo.
tvOS renders at 1920x1080 pt @1x, so 1 pt = 1 px on a 1080p TV.

## Layout
- Safe area: inset primary content **60 pt top/bottom, 80 pt sides**.
- Grid (unfocused content width, 40 pt horizontal spacing, >=100 pt vertical spacing):
  2 col 860 · 3 col 560 · 4 col 410 · 5 col 320 · **6 col 260** · 7 col 217 · 8 col 184 · 9 col 160.
- Leave room so a focused (scaled) item never overlaps neighbours or important info.

## App icons
- Landscape rounded rectangle, layout size **800x480 px (5:3)**, 2–5 parallax layers.
- System applies the rounded corners; text must sit on the top layer (edges get cropped on focus).
- Focus: "elevates to the foreground ..., gently sways while the surface illuminates" (parallax + specular sheen).

## Focus scale (derived from Top Shelf unfocused -> focused sizes)
| Item | Unfocused | Focused | Scale |
|---|---|---|---|
| 16:9 card | 782x440 | 852x479 | **1.09** |
| Square | 500x500 | 570x570 | 1.14 |
| Poster 2:3 | 333x570 | 380x570 | (width) 1.14 |
| Wide carousel | 1740x560 | 1740x620 | (height) 1.11 |

## Top Shelf
- Full-width artwork; wide/parallax image **2320x720 pt** (1920 visible + overscan for parallax).
- Shows content for the focused Dock app; Dock holds favourite apps below it.

## Materials
- Liquid Glass = functional layer floating above content (Dock, Top Shelf chrome, Control Center).
  Never in the content layer; use sparingly.
- Clear glass over bright content: add a **35% dark dimming layer** behind it.
- Glass adapts light/dark to the content underneath; specular highlights + translucency.

## Not specified by Apple (inferred, to tune visually)
- Focus animation duration/easing (start ~200 ms, decelerate); parallax tilt amount.
