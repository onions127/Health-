# Design & Product Decisions — Health++ Redesign v2

## 1. Brand Naming (5 Candidates Evaluated)
We evaluated five short (1–2 word) names centered on recovery and readiness (excluding the banned name "Nadir"):
1. **Health++** *(Chosen)* — Crisp, memorable, zero trademark/brand collision with existing sleep rings or straps, and signals elevated personal health for a data-savvy user.
2. **RestArc** — Evokes the overnight recovery curve and daily readiness arc.
3. **EvenKeel** — Calm, human phrase for staying balanced within your normal baseline.
4. **Daybreak** — Signals morning readiness and starting each day informed.
5. **TrueRest** — Simple, recovery-first name focused on restorative sleep and HRV.

**Decision:** Selected **Health++** per project guidelines, as common single-word wellness names collide with existing consumer apps while **Health++** is unmistakable and fits a home-server-friendly companion app.

## 2. Typography & Strictly 3 Font Sizes
- Chosen typeface: **Manrope** (loaded via Google Fonts CDN) for its warm geometric clarity and tabular-numeral legibility.
- Enforced strictly **3 font sizes per screen**:
  - `48px` (`font-weight: 700`) for hero scores and primary metric numbers.
  - `16px` (`font-weight: 600`) for card titles, primary verdict copy, and list headers.
  - `13px` (`font-weight: 500`) for labels, units next to numbers (`64 ms`), context chips (`↑ 8% vs normal`), and bottom navigation labels.

## 3. Above-the-Fold Word Budget & Anti-Slop Copy
- Default Today view contains **27 words total** across the entire 360×800 viewport (well under the 30-word limit), ensuring a <5-second glance test.
- Every number is paired with human baseline context (`↑ 6 vs normal`, `↑ 8% vs normal`, `Within normal · 7k–10k`, `Lowest in 2 weeks`).
- Removed all clinical/dashboard jargon, all-caps labels, and tabular grids.

## 4. Navigation & 2-Tap History Access
- Every metric's 90-day history is reachable in **≤2 taps** via two paths:
  1. **Trends Tab (1 tap) → Metric Card (2nd tap)** opens the full Detail view with Week / Month / 3M / Year toggle.
  2. **Today Tab → Expand Score Card (1 tap) → Sub-metric Row (2nd tap)** opens the same Detail view directly.
- Out-of-bounds alerts (SpO2 / Skin Temp) stay hidden during normal days and surface above the Daily Score only when abnormal (`Today / SpO2 alert` state).

## 5. Figma Push & MCP Tool Availability
- Created new Figma Design file **Health++ Redesign v2** (`https://www.figma.com/design/s2d0UL8fEIH34jU2jlmknL`) via `create_new_file`.
- Attempted `generate_figma_design` twice per protocol; the connected Figma remote MCP server returned `Tool generate_figma_design not found` (only available on the desktop plugin server), so we logged the failure after 2 tries and did not call any disallowed fallback tools.
