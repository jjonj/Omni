# Specification - TFT Unit Role Columns

## Overview
The goal of this track is to enhance the TFT unit pool interface by organizing units into distinct columns based on their combat role (Tank, AP Carry, AD Carry, Fighter), while maintaining the existing cost-based row structure. This matrix-style layout will help users quickly identify units that fit their composition's needs.

## Functional Requirements
1.  **Data Schema Extension:**
    -   Update `assets/tft/data/set16.json` to add a `"role"` property to each unit object.
    -   Role values: `"Tank"`, `"AP Carry"`, `"AD Carry"`, `"Fighter"`.
2.  **UI Layout Transformation:**
    -   Modify `TFT.html` styles and `tft.js` rendering logic to display the unit pool as a 2D matrix.
    -   **Rows:** Unit Cost (1 through 5).
    -   **Columns:** Role (Tank, AP Carry, AD Carry, Fighter).
    -   Units must be vertically aligned under their respective role headers across all rows.
    -   Empty spaces should be left in the grid if a role has no units for a specific cost.
3.  **Default Role Assignments:**
    -   **Tank:** Blitzcrank, Illaoi, Jarvan IV, Rumble, Shen, Cho'Gath, Neeko, Poppy, Vi, Xin Zhao, Yorick, Darius, Dr. Mundo, Kennen, Kobuko & Yuumi, Leona, Loris, Nautilus, Sejuani, Braum, Garen, Nasus, Renekton, Rift Herald, Singed, Skarner, Swain, Taric, Wukong, Tibbers, Ornn, Baron Naashor, Brock, Galio, T-Hex.
    -   **AP Carry:** Anivia, Kog'Maw, Lulu, Sona, Ekko, Teemo, Twisted Fate, Ahri, Gwen, LeBlanc, Malzahar, Milio, Zoe, Lissandra, Lux, Seraphine, Veigar, Annie, Azir, Fiddlesticks, Zilean, Ryze, Sylas, Xerath, Aurelion Sol, Bard, Nidalee.
    -   **AD Carry:** Jhin, Caitlyn, Aphelios, Ashe, Graves, Tristana, Jinx, Vayne, Miss Fortune, Kai'Sa, Lucian & Senna, Kindred.
    -   **Fighter:** Briar, Qiyana, Viego, Rek'Sai, Tryndamere, Yasuo, Draven, Gangplank, Ambessa, Bel'Veth, Diana, Warwick, Yunara, Aatrox, Mel, Sett, Shyvana, Zaahen, Yone.

## Non-Functional Requirements
- **Visual Consistency:** Ensure the grid headers and spacing match the existing UI aesthetic.
- **Zero Performance Impact:** The reorganization should not slow down the initial load or filtering of unit pools.

## Acceptance Criteria
- [ ] Each unit in the pool is assigned one of the four specified roles in the JSON data.
- [ ] The unit pool is visually organized into 4 vertical columns labeled by role.
- [ ] Units are grouped by cost within their respective columns.
- [ ] Alignment is consistent (e.g., all 1-cost Tanks are in row 1, column 1; all 2-cost Tanks are in row 2, column 1).
- [ ] Interactions (click, drag, right-click to disable) remain fully functional.

## Out of Scope
- Dynamic role assignment logic (roles are static in data).
- User-customizable role assignments in the UI.
