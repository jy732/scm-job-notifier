package com.github.jingyangyu.scmjobnotifier.service.classification;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Constants used by {@link JobTitleFilter} for local pre-filtering and Stage-1 classification of
 * SCM job postings.
 *
 * <p>See {@code docs/technical-design.html} §5–§6 and Appendix A for the taxonomy these lists are
 * derived from. The key SCM-specific differences from a SWE notifier:
 *
 * <ul>
 *   <li>{@code intern} is NOT excluded — internships are a target track.
 *   <li>Location filtering is California-only, not US-wide.
 *   <li>Relevance requires a supply-chain keyword, not an engineering keyword.
 *   <li>Stage-1 only fires on explicit level markers + entry-only role nouns; bare functional
 *       titles ("Supply Chain Analyst", "Buyer") are deliberately deferred to Stages 2–3.
 * </ul>
 */
final class FilterKeywords {

    private FilterKeywords() {}

    // ── Tier 1: Hard exclude — above entry level, drop completely ──
    // Seniority only. NOTE: "intern"/"co-op" are intentionally absent — they are a target track.
    static final List<String> EXCLUDE_KEYWORDS =
            List.of(
                    "senior",
                    "sr.",
                    "sr ",
                    "staff",
                    "principal",
                    "manager",
                    "director",
                    "vp ",
                    "vice president",
                    "head of",
                    "chief",
                    "supervisor",
                    "president");

    // "lead" as a standalone word only — must not match "Leadership Development Program", which is
    // a strong ENTRY_LEVEL signal. \b after "lead" fails on "leadership" (followed by 'e').
    static final Pattern EXCLUDE_LEAD_PATTERN = Pattern.compile("(?i)\\blead\\b");

    // ── Tier 1b: Non-SCM hard exclude (guarded) ──
    // Drop technical/IC roles (engineer/scientist/developer) that leak in when an ambiguous SCM
    // keyword appears in a software/hardware title (e.g. "Software Engineer, Autonomous Freight
    // Systems", "Materials Engineer, Metals"). These must be dropped BEFORE Stage 1/2, which would
    // otherwise auto-classify them ENTRY from a title marker or YOE and skip Gemini entirely.
    static final Pattern NON_SCM_PATTERN =
            Pattern.compile("(?i)\\b(engineer|scientist|developer)\\b");

    // Guard for NON_SCM_PATTERN: an engineer/scientist/developer title is kept (not excluded) when
    // it also contains one of these unambiguous SCM anchors — this rescues genuine SCM engineering
    // roles ("Supplier Development Engineer"/SQE, "Supply Chain Engineer", "Sourcing Engineer").
    // Deliberately omits ambiguous words (materials/freight/planner/transportation) that also occur
    // in software/AV/materials-science titles.
    static final List<String> SCM_ANCHOR_KEYWORDS =
            List.of(
                    "supply chain",
                    "supplier",
                    "sourcing",
                    "procurement",
                    "purchasing",
                    "logistics",
                    "warehouse",
                    "inventory",
                    "commodity",
                    "s&op",
                    "replenishment");

    // ── Stage 1A: INTERNSHIP — explicit intern token (highest priority) ──
    // \b guards keep "intern" from matching "internal"/"international".
    static final Pattern INTERNSHIP_PATTERN =
            Pattern.compile(
                    "(?i)(\\bintern(ship|s)?\\b"
                            + "|\\bco[-\\s]?op\\b"
                            + "|\\bsummer\\s+(analyst|associate|scholar)\\b)");

    // ── Stage 1B: ENTRY_LEVEL — explicit early-career markers ──
    // new/recent/university/college grad · entry-level · junior/jr · "<role> I/1" (not II) ·
    // rotational / leadership development / management trainee / development program · campus /
    // early career / trainee.
    static final Pattern ENTRY_LEVEL_PATTERN =
            Pattern.compile(
                    "(?i)("
                            + "\\b(new|recent|university|college)\\s+grad(uate)?\\b"
                            + "|\\bentry[-\\s]?level\\b"
                            + "|\\b(jr\\.?|junior)\\b"
                            + "|\\b(analyst|coordinator|specialist|planner|buyer|associate)\\s*"
                            + "(i|1)\\b"
                            + "|\\b(rotational|leadership\\s+development|management\\s+trainee)\\b"
                            + "|\\bdevelopment\\s+program\\b"
                            + "|\\b(campus|early\\s+career|trainee)\\b"
                            + ")");

    // ── Stage 1C: ENTRY_LEVEL — entry-only role nouns (medium confidence) ──
    // In SCM these are never used for senior roles (a senior person is a "Manager"/"Lead",
    // already excluded at Tier 1). Substring match on the lowercased title.
    static final List<String> ENTRY_ROLE_NOUNS =
            List.of("coordinator", "assistant", "administrator");

    // ── Tier 3: SCM relevance gate — title must contain at least one of these ──
    // This gate is the primary guard that keeps non-SCM roles (SWE, finance, sales, HR) out of the
    // notification stream, so it is kept reasonably precise. Gemini (Stage 3) is a further
    // backstop.
    static final List<String> SCM_KEYWORDS =
            List.of(
                    "supply chain",
                    "logistics",
                    "procurement",
                    "sourcing",
                    "purchasing",
                    "buyer",
                    "demand planning",
                    "supply planning",
                    "production planning",
                    "materials planning",
                    "material planning",
                    "inventory",
                    "warehouse",
                    "fulfillment",
                    "fulfilment",
                    "distribution",
                    "transportation",
                    "category management",
                    "commodity",
                    "vendor management",
                    "replenishment",
                    "planner",
                    "mrp",
                    "s&op",
                    "freight",
                    "customs",
                    "order management",
                    "3pl",
                    "supplier",
                    "materials",
                    "material handler",
                    "import/export",
                    "supply chain operations",
                    "warehouse operations");

    // ── Location: California detection ──
    // Matches ", CA" as a whole token (so it won't fire on "Canada"): comma, optional space, "ca",
    // then a word boundary (end, space, comma, or a zip digit).
    static final Pattern CA_STATE_PATTERN = Pattern.compile("(?i),\\s*ca\\b");

    // Known California cities/metros. Ambiguous names that also exist elsewhere (e.g. "Ontario" =
    // Canada, "San Jose" = Costa Rica) are gated by a non-US-country reject check in the filter.
    static final List<String> CA_CITIES =
            List.of(
                    "california",
                    "san francisco",
                    "south san francisco",
                    "san jose",
                    "oakland",
                    "fremont",
                    "sunnyvale",
                    "santa clara",
                    "mountain view",
                    "palo alto",
                    "cupertino",
                    "milpitas",
                    "pleasanton",
                    "san mateo",
                    "redwood city",
                    "menlo park",
                    "san bruno",
                    "san carlos",
                    "foster city",
                    "campbell",
                    "berkeley",
                    "emeryville",
                    "hercules",
                    "san ramon",
                    "dublin, ca",
                    "livermore",
                    "hayward",
                    "los angeles",
                    "long beach",
                    "irvine",
                    "anaheim",
                    "santa ana",
                    "el segundo",
                    "torrance",
                    "city of industry",
                    "costa mesa",
                    "newport beach",
                    "manhattan beach",
                    "redondo beach",
                    "hawthorne",
                    "culver city",
                    "pasadena",
                    "burbank",
                    "glendale",
                    "thousand oaks",
                    "carlsbad",
                    "san diego",
                    "goleta",
                    "santa barbara",
                    "sacramento",
                    "fresno",
                    "bakersfield",
                    "riverside",
                    "ontario, ca",
                    "san bernardino");

    // ── Location: known non-US countries — reject even if an ambiguous CA city name appears ──
    static final String[] NON_US_COUNTRIES = {
        "canada",
        "united kingdom",
        "germany",
        "france",
        "india",
        "australia",
        "japan",
        "singapore",
        "ireland",
        "mexico",
        "brazil",
        "china",
        "israel",
        "taiwan",
        "south korea",
        "korea",
        "netherlands",
        "sweden",
        "switzerland",
        "poland",
        "czech",
        "denmark",
        "finland",
        "norway",
        "new zealand",
        "philippines",
        "thailand",
        "hong kong",
        "indonesia",
        "malaysia",
        "vietnam",
        "spain",
        "italy",
        "portugal",
        "costa rica"
    };
}
