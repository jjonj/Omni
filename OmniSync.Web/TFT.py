import itertools

EMBLEMS = ["Demacia", "Freljord", "Yordle"]
MUST_INCLUDE = ""

# --- SCORING AND CONFIGURATION CONSTANTS ---
BREAKPOINT_SCORE_MULTIPLIER = 1000
UNIQUE_TRAIT_SCORE = 1900  

LOCKED_UNIT_PENALTY = 500
YORDLE_PENALTY = 200
UNIT_COST_TIEBREAKER_WEIGHT = 10

# Hard Constraints
INVALID_COMP_PENALTY = 10**8
MISSING_CARRY_PENALTY = 10**5
MAX_FIVE_COSTS_LVL_8 = 2
EXCESS_FIVE_COST_PENALTY = 1000

# Trait Difficulty Constraints
MAX_TRAIT_COUNT = 7
FORBIDDEN_SHURIMA_MIN = 3

SMALL_TRAIT_BONUS = 1     # Tie-breaker for Quickstriker, Piltover, Arcanist, Targon
BONUS_TRAITS = ["Quickstriker", "Piltover", "Targon"]
IGNORE_TRAITS = ["Ixtal", "Shadow Isles"]


# Unit Specifics
ANNIE_SLOTS = 2
ANNIE_ARCANIST_COUNT = 2
SYLAS_FORBIDDEN_NAMES = ["Jarvan IV", "Lux", "Garen"]



# Algorithm Settings
CANDIDATE_POOL_SIZE = 22

# --- DATA INITIALIZATION ---
UNITS = [
    {"name": "Anivia", "cost": 1, "traits": ["Freljord", "Invoker"], "is_carry": True, "locked": False},
    {"name": "Blitzcrank", "cost": 1, "traits": ["Zaun", "Juggernaut"], "is_carry": False, "locked": False},
    {"name": "Briar", "cost": 1, "traits": ["Noxus", "Slayer", "Juggernaut"], "is_carry": True, "locked": False},
    {"name": "Caitlyn", "cost": 1, "traits": ["Piltover", "Longshot"], "is_carry": True, "locked": False},
    {"name": "Illaoi", "cost": 1, "traits": ["Bilgewater", "Bruiser"], "is_carry": False, "locked": False},
    {"name": "Jarvan IV", "cost": 1, "traits": ["Demacia", "Defender"], "is_carry": False, "locked": False},
    {"name": "Jhin", "cost": 1, "traits": ["Ionia", "Gunslinger"], "is_carry": True, "locked": False},
    {"name": "Kog'Maw", "cost": 1, "traits": ["Void", "Arcanist", "Longshot"], "is_carry": True, "locked": False},
    {"name": "Lulu", "cost": 1, "traits": ["Yordle", "Arcanist"], "is_carry": True, "locked": False},
    {"name": "Qiyana", "cost": 1, "traits": ["Ixtal", "Slayer"], "is_carry": True, "locked": False},
    {"name": "Rumble", "cost": 1, "traits": ["Yordle", "Defender"], "is_carry": False, "locked": False},
    {"name": "Shen", "cost": 1, "traits": ["Ionia", "Bruiser"], "is_carry": False, "locked": False},
    {"name": "Sona", "cost": 1, "traits": ["Demacia", "Invoker"], "is_carry": True, "locked": False},
    {"name": "Viego", "cost": 1, "traits": ["Shadow Isles", "Quickstriker"], "is_carry": True, "locked": False},
    {"name": "Aphelios", "cost": 2, "traits": ["Targon"], "is_carry": True, "locked": False},
    {"name": "Ashe", "cost": 2, "traits": ["Freljord", "Quickstriker"], "is_carry": True, "locked": False},
    {"name": "Cho'Gath", "cost": 2, "traits": ["Void", "Juggernaut"], "is_carry": False, "locked": False},
    {"name": "Ekko", "cost": 2, "traits": ["Zaun", "Disruptor"], "is_carry": True, "locked": False},
    {"name": "Graves", "cost": 2, "traits": ["Bilgewater", "Gunslinger"], "is_carry": True, "locked": False},
    {"name": "Neeko", "cost": 2, "traits": ["Ixtal", "Arcanist", "Defender"], "is_carry": False, "locked": False},
    {"name": "Orianna", "cost": 2, "traits": ["Piltover", "Invoker"], "is_carry": False, "locked": True},
    {"name": "Poppy", "cost": 2, "traits": ["Demacia", "Juggernaut", "Yordle"], "is_carry": False, "locked": False},
    {"name": "Rek'Sai", "cost": 2, "traits": ["Void", "Vanquisher"], "is_carry": True, "locked": False},
    {"name": "Sion", "cost": 2, "traits": ["Noxus", "Bruiser"], "is_carry": False, "locked": False},
    {"name": "Teemo", "cost": 2, "traits": ["Yordle", "Longshot"], "is_carry": True, "locked": False},
    {"name": "Tristana", "cost": 2, "traits": ["Yordle", "Gunslinger"], "is_carry": True, "locked": False},
    {"name": "Tryndamere", "cost": 2, "traits": ["Freljord", "Slayer"], "is_carry": True, "locked": False},
    {"name": "Twisted Fate", "cost": 2, "traits": ["Bilgewater", "Quickstriker"], "is_carry": True, "locked": False},
    {"name": "Vi", "cost": 2, "traits": ["Zaun", "Defender", "Piltover"], "is_carry": False, "locked": False},
    {"name": "Xin Zhao", "cost": 2, "traits": ["Demacia", "Ionia", "Warden"], "is_carry": False, "locked": False},
    {"name": "Yasuo", "cost": 2, "traits": ["Ionia", "Slayer"], "is_carry": True, "locked": False},
    {"name": "Yorick", "cost": 2, "traits": ["Shadow Isles", "Warden"], "is_carry": False, "locked": True},
    {"name": "Ahri", "cost": 3, "traits": ["Ionia", "Arcanist"], "is_carry": True, "locked": False},
    {"name": "Darius", "cost": 3, "traits": ["Noxus", "Defender"], "is_carry": False, "locked": True},
    {"name": "Dr. Mundo", "cost": 3, "traits": ["Zaun", "Bruiser"], "is_carry": False, "locked": False},
    {"name": "Draven", "cost": 3, "traits": ["Noxus", "Vanquisher"], "is_carry": True, "locked": False},
    {"name": "Gangplank", "cost": 3, "traits": ["Bilgewater", "Slayer", "Vanquisher"], "is_carry": True, "locked": False},
    {"name": "Gwen", "cost": 3, "traits": ["Shadow Isles", "Disruptor"], "is_carry": True, "locked": True},
    {"name": "Jinx", "cost": 3, "traits": ["Zaun", "Gunslinger"], "is_carry": True, "locked": False},
    {"name": "Kennen", "cost": 3, "traits": ["Ionia", "Defender", "Yordle"], "is_carry": False, "locked": False},
    {"name": "Kobuko & Yuumi", "cost": 3, "traits": ["Bruiser", "Invoker", "Yordle"], "is_carry": False, "locked": False},
    {"name": "LeBlanc", "cost": 3, "traits": ["Noxus", "Invoker"], "is_carry": False, "locked": True},
    {"name": "Leona", "cost": 3, "traits": ["Targon"], "is_carry": False, "locked": False},
    {"name": "Loris", "cost": 3, "traits": ["Piltover", "Warden"], "is_carry": False, "locked": False},
    {"name": "Malzahar", "cost": 3, "traits": ["Void", "Disruptor"], "is_carry": True, "locked": False},
    {"name": "Milio", "cost": 3, "traits": ["Ixtal", "Invoker"], "is_carry": True, "locked": False},
    {"name": "Nautilus", "cost": 3, "traits": ["Bilgewater", "Juggernaut", "Warden"], "is_carry": False, "locked": False},
    {"name": "Sejuani", "cost": 3, "traits": ["Freljord", "Defender"], "is_carry": False, "locked": False},
    {"name": "Vayne", "cost": 3, "traits": ["Demacia", "Longshot"], "is_carry": True, "locked": False},
    {"name": "Zoe", "cost": 3, "traits": ["Targon"], "is_carry": False, "locked": False},
    {"name": "Ambessa", "cost": 4, "traits": ["Noxus", "Vanquisher"], "is_carry": True, "locked": False},
    {"name": "Bel'Veth", "cost": 4, "traits": ["Void", "Slayer"], "is_carry": True, "locked": False},
    {"name": "Braum", "cost": 4, "traits": ["Freljord", "Warden"], "is_carry": False, "locked": False},
    {"name": "Diana", "cost": 4, "traits": ["Targon"], "is_carry": True, "locked": False},
    {"name": "Fizz", "cost": 4, "traits": ["Bilgewater", "Yordle"], "is_carry": False, "locked": True},
    {"name": "Garen", "cost": 4, "traits": ["Demacia", "Juggernaut"], "is_carry": False, "locked": False},
    {"name": "Kai'Sa", "cost": 4, "traits": ["Longshot", "Void"], "is_carry": True, "locked": False},
    {"name": "Kalista", "cost": 4, "traits": ["Shadow Isles", "Vanquisher"], "is_carry": True, "locked": True},
    {"name": "Lissandra", "cost": 4, "traits": ["Freljord", "Invoker"], "is_carry": True, "locked": False},
    {"name": "Lux", "cost": 4, "traits": ["Demacia", "Arcanist"], "is_carry": True, "locked": False},
    {"name": "Miss Fortune", "cost": 4, "traits": ["Bilgewater", "Gunslinger"], "is_carry": True, "locked": False},
    {"name": "Nasus", "cost": 4, "traits": ["Shurima", "Juggernaut"], "is_carry": False, "locked": True},
    {"name": "Renekton", "cost": 4, "traits": ["Shurima", "Juggernaut"], "is_carry": False, "locked": True},
    {"name": "Rift Herald", "cost": 4, "traits": ["Bruiser", "Void"], "is_carry": False, "locked": True},
    {"name": "Seraphine", "cost": 4, "traits": ["Piltover", "Disruptor"], "is_carry": True, "locked": False},
    {"name": "Singed", "cost": 4, "traits": ["Zaun", "Juggernaut"], "is_carry": False, "locked": True},
    {"name": "Skarner", "cost": 4, "traits": ["Ixtal"], "is_carry": False, "locked": True},
    {"name": "Swain", "cost": 4, "traits": ["Noxus", "Arcanist", "Juggernaut"], "is_carry": False, "locked": False},
    {"name": "Taric", "cost": 4, "traits": ["Targon"], "is_carry": False, "locked": False},
    {"name": "Veigar", "cost": 4, "traits": ["Yordle", "Arcanist"], "is_carry": True, "locked": True},
    {"name": "Warwick", "cost": 4, "traits": ["Zaun", "Quickstriker"], "is_carry": True, "locked": True},
    {"name": "Wukong", "cost": 4, "traits": ["Ionia", "Bruiser"], "is_carry": False, "locked": False},
    {"name": "Yunara", "cost": 4, "traits": ["Ionia", "Quickstriker"], "is_carry": True, "locked": False},
    {"name": "Aatrox", "cost": 5, "traits": ["World Ender", "Darkin", "Slayer"], "is_carry": False, "locked": True},
    {"name": "Annie", "cost": 5, "traits": ["Dark Child", "Arcanist", "Arcanist"], "is_carry": True, "locked": False, "slots": ANNIE_SLOTS},
    {"name": "Azir", "cost": 5, "traits": ["Emperor", "Shurima"], "is_carry": True, "locked": False},
    {"name": "Fiddlesticks", "cost": 5, "traits": ["Harvester", "Vanquisher"], "is_carry": False, "locked": False},
    {"name": "Kindred", "cost": 5, "traits": ["Eternal", "Quickstriker"], "is_carry": True, "locked": False},
    {"name": "Lucian & Senna", "cost": 5, "traits": ["Soulbound", "Gunslinger"], "is_carry": True, "locked": False},
    {"name": "Mel", "cost": 5, "traits": ["Noxus", "Disruptor"], "is_carry": False, "locked": True},
    {"name": "Ornn", "cost": 5, "traits": ["Blacksmith"], "is_carry": False, "locked": False},
    {"name": "Ryze", "cost": 5, "traits": ["Rune Mage"], "is_carry": True, "locked": True},
    {"name": "Sett", "cost": 5, "traits": ["The Boss", "Ionia"], "is_carry": False, "locked": True},
    {"name": "Shyvana", "cost": 5, "traits": ["Dragonborn", "Juggernaut"], "is_carry": True, "locked": False},
    {"name": "Sylas", "cost": 5, "traits": ["Chainbreaker", "Arcanist", "Defender"], "is_carry": True, "locked": True},
    {"name": "Tahm Kench", "cost": 5, "traits": ["Glutton", "Bilgewater", "Bruiser"], "is_carry": False, "locked": True},
    {"name": "Thresh", "cost": 5, "traits": ["Shadow Isles", "Warden"], "is_carry": False, "locked": True},
    {"name": "Volibear", "cost": 5, "traits": ["Freljord", "Bruiser"], "is_carry": False, "locked": True},
    {"name": "Xerath", "cost": 5, "traits": ["Ascendant", "Shurima"], "is_carry": True, "locked": True},
    {"name": "Ziggs", "cost": 5, "traits": ["Yordle", "Zaun", "Longshot"], "is_carry": True, "locked": True},
    {"name": "Zilean", "cost": 5, "traits": ["Chronokeeper", "Invoker"], "is_carry": True, "locked": False},
]

TRAIT_BREAKPOINTS = {
    "Bilgewater": [3, 5, 7], "Demacia": [3, 5, 7], "Freljord": [3, 5, 7],
    "Ionia": [3, 5, 7], "Noxus": [3, 5, 7, 10], "Piltover": [2, 4, 6],
    "Void": [3, 6, 8, 10], "Yordle": [2, 4, 6, 8], "Zaun": [3, 5, 7],
    "Arcanist": [2, 4, 6], "Bruiser": [2, 4, 6], "Defender": [2, 4, 6],
    "Disruptor": [2, 4], "Gunslinger": [2, 4, 6], "Invoker": [2, 4, 6],
    "Juggernaut": [2, 4, 6], "Longshot": [2, 3, 4, 5], "Quickstriker": [2, 3, 4, 5],
    "Slayer": [2, 4, 6], "Vanquisher": [2, 3, 4, 5], "Warden": [2, 3, 4, 5]
}

# --- CORE FUNCTIONS ---

def score_board(board, emblems, target_size):
    counts = {}
    names = [u['name'] for u in board]
    
    # Check Special Rule: Sylas vs Demacia Trio
    if "Sylas" in names and any(x in names for x in SYLAS_FORBIDDEN_NAMES):
        return -INVALID_COMP_PENALTY

    # Trait Counting Logic
    for u in board:
        for t in u['traits']:
            if t in IGNORE_TRAITS: continue
            # Annie logic: counts for 2 Arcanists
            increment = ANNIE_ARCANIST_COUNT if (u['name'] == "Annie" and t == "Arcanist") else 1
            counts[t] = counts.get(t, 0) + increment
    
    for emb in emblems:
        if emb not in IGNORE_TRAITS:
            counts[emb] = counts.get(emb, 0) + 1
            
    score = 0
    # Evaluate Traits
    for trait, count in counts.items():
        if count > MAX_TRAIT_COUNT or (trait == "Shurima" and count >= FORBIDDEN_SHURIMA_MIN):
            score -= INVALID_COMP_PENALTY
            
        if trait in TRAIT_BREAKPOINTS:
            reached = [b for b in TRAIT_BREAKPOINTS[trait] if b <= count]
            if reached:
                score += max(reached) * BREAKPOINT_SCORE_MULTIPLIER
        elif count == 1:
            score += UNIQUE_TRAIT_SCORE

    # Carry Logic
    carries = [u for u in board if u['is_carry']]
    if target_size == 6:
        if len(carries) < 1: score -= MISSING_CARRY_PENALTY
    else:
        # Level 8 requirement: 2 carries, at least one is 4 or 5 cost
        high_cost_carries = [u for u in carries if u['cost'] >= 4]
        if len(carries) < 2 or len(high_cost_carries) < 1:
            score -= MISSING_CARRY_PENALTY

    # Unit-based Scoring (Penalties, Bonuses, and Tie-breakers)
    five_cost_count = 0
    for u in board:
        if u['cost'] == 5: five_cost_count += 1
        if u['locked']: score -= LOCKED_UNIT_PENALTY
        
        for t in u['traits']:
            if t == "Yordle": score -= YORDLE_PENALTY
            if t in BONUS_TRAITS: score += SMALL_TRAIT_BONUS
            
        score += u['cost'] * UNIT_COST_TIEBREAKER_WEIGHT
        
    # Level 8 high-cost preference management
    if target_size >= 8 and five_cost_count > MAX_FIVE_COSTS_LVL_8:
        score -= (five_cost_count - MAX_FIVE_COSTS_LVL_8) * EXCESS_FIVE_COST_PENALTY
        
    return score

def get_combos(target_slots, pool):
    """Recursive generator to handle variable slot sizes (like Annie)."""
    if target_slots == 0: yield []
    if target_slots < 0 or not pool: return
    
    # Pick first unit
    u = pool[0]
    u_slots = u.get('slots', 1)
    for c in get_combos(target_slots - u_slots, pool[1:]):
        yield [u] + c
        
    # Skip first unit
    for c in get_combos(target_slots, pool[1:]):
        yield c

def find_best_boards(pool, size, emblems, must_include_name=None, extra_slot=False):
    target_slots = size + 1 if extra_slot else size
    fixed_units = [u for u in UNITS if u['name'] == must_include_name] if must_include_name else []
    
    # Filter candidates based on synergy, carrying potential, or cost for efficiency
    synergy_base = set(emblems + BONUS_TRAITS)
    for f in fixed_units: synergy_base.update(f['traits'])
    
    candidates = [u for u in pool if u['name'] not in [f['name'] for f in fixed_units]]
    candidates = sorted(candidates, 
                        key=lambda x: (any(t in synergy_base for t in x['traits']), x['cost']), 
                        reverse=True)[:CANDIDATE_POOL_SIZE]

    needed_slots = target_slots - sum(u.get('slots', 1) for u in fixed_units)
    
    results = []
    for combo in get_combos(needed_slots, candidates):
        current_board = combo + fixed_units
        score = score_board(current_board, emblems, target_slots)
        results.append((score, current_board))
    
    results.sort(key=lambda x: x[0], reverse=True)
    return results[:3]

def format_output(results):
    for score, board in results:
        # Sort by cost (low to high), then by name (alphabetical)
        sorted_board = sorted(board, key=lambda x: (x['cost'], x['name']))
        unit_names = [u['name'] for u in sorted_board]
        print(f"Score {score}: {unit_names}")

# --- EXECUTION ---

print(f"--- LEVEL 6 BOARDS (Must include {MUST_INCLUDE}, Emblems: {EMBLEMS}) ---")
POOL_6 = [u for u in UNITS if u['cost'] <= 3]
results_6 = find_best_boards(POOL_6, 6, EMBLEMS, MUST_INCLUDE)
format_output(results_6)

print(f"\n--- LEVEL 8 BOARDS (Emblems: {EMBLEMS}) ---")
# Level 8 can use any unit
results_8 = find_best_boards(UNITS, 8, EMBLEMS)
format_output(results_8)