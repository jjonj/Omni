/* =========================================
   USER CONFIGURATION ZONE
   Edit the duration (dur) or names below.
   dur = minutes.
   ========================================= */
const DAY_TEMPLATES = {
    // 80% - 100% Condition
    standard: [
        { name: "BOOT SEQUENCE",       dur: 120, type: "boot" },
        { name: "GAME DEV (CORE)",     dur: 180, type: "work" },
        { name: "RECOVERY / EXERCISE", dur: 60,  type: "rest" },
        { name: "FLEX",                dur: 120, type: "flex" },
        { name: "LEAGUE BLOCK A",      dur: 150, type: "league" },
        { name: "FLEX POST",           dur: 30,  type: "flex" },
        { name: "DINNER",              dur: 30,  type: "rest" },
        { name: "LEAGUE BLOCK B",      dur: 150, type: "league" },
        { name: "SHUTDOWN",            dur: 60,  type: "bed" },
        { name: "SLEEP (9 HOURS)",     dur: 540, type: "sleep" }
    ],
    // 50% - 79% Condition (Intermediate)
    intermediate: [
        { name: "BOOT SEQUENCE",       dur: 120, type: "boot" },
        { name: "GAME DEV (LIGHT)",    dur: 120, type: "work" },
        { name: "RECOVERY / EXERCISE", dur: 60,  type: "rest" },
        { name: "FLEX",                dur: 150, type: "flex" },
        { name: "LEAGUE BLOCK A",      dur: 150, type: "league" },
        { name: "DINNER",              dur: 60,  type: "rest" },
        { name: "RELAX / VODS",        dur: 180, type: "rest" },
        { name: "SHUTDOWN",            dur: 60,  type: "bed" },
        { name: "SLEEP (9 HOURS)",     dur: 540, type: "sleep" }
    ],
    // 20% - 49% Condition (Foggy)
    fog: [
        { name: "BOOT SEQUENCE",       dur: 150, type: "boot" },
        { name: "ZOMBIE TASKS",        dur: 90,  type: "flex" },
        { name: "RECOVERY",            dur: 60,  type: "rest" },
        { name: "PASSIVE MEDIA",       dur: 180, type: "rest" },
        { name: "DINNER",              dur: 60,  type: "rest" },
        { name: "RELAX",               dur: 240, type: "rest" },
        { name: "SHUTDOWN",            dur: 60,  type: "bed" },
        { name: "SLEEP (9 HOURS)",     dur: 540, type: "sleep" }
    ],
    // 0% - 19% Condition (Maintenance)
    maintenance: [
        { name: "SLOW BOOT",           dur: 180, type: "boot" },
        { name: "REST / AUDIO",        dur: 120, type: "rest" },
        { name: "WALK / LIGHT",        dur: 60,  type: "rest" },
        { name: "PASSIVE MEDIA",       dur: 420, type: "rest" },
        { name: "SHUTDOWN",            dur: 60,  type: "bed" },
        { name: "SLEEP (9 HOURS)",     dur: 540, type: "sleep" }
    ],
    // Wednesday / Paper Route
    chaos: [
        { name: "BOOT SEQUENCE",       dur: 120, type: "boot" },
        { name: "PAPER ROUTE",         dur: 240, type: "chaos" },
        { name: "HARD RECOVERY",       dur: 90,  type: "rest" },
        { name: "SINGLE FOCUS BLOCK",  dur: 120, type: "work" },
        { name: "DINNER",              dur: 60,  type: "rest" },
        { name: "PASSIVE EVENING",     dur: 210, type: "rest" },
        { name: "SHUTDOWN",            dur: 60,  type: "bed" },
        { name: "SLEEP (9 HOURS)",     dur: 540, type: "sleep" }
    ],
    // Obsession Override
    obsession: [
        { name: "BOOT SEQUENCE",       dur: 120, type: "boot" },
        { name: "SIDE QUEST (OBSESS)", dur: 180, type: "work" },
        { name: "RECOVERY",            dur: 60,  type: "rest" },
        { name: "FLEX",                dur: 60,  type: "flex" },
        { name: "LEAGUE BLOCK A",      dur: 150, type: "league" },
        { name: "DINNER",              dur: 60,  type: "rest" },
        { name: "LEAGUE BLOCK B",      dur: 150, type: "league" },
        { name: "SHUTDOWN",            dur: 60,  type: "bed" },
        { name: "SLEEP (9 HOURS)",     dur: 540, type: "sleep" }
    ]
};