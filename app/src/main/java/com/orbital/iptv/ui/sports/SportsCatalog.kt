package com.orbital.iptv.ui.sports

/**
 * What the Sports Bar shows: top-level sports, each with optional sub-categories (e.g. the
 * football leagues) and one or more views (fixtures, tables, standings...).
 */
object SportsCatalog {

    /**
     * How a sport's data is shaped, which decides its parser, row style and navigation.
     * LISTINGS sports aren't on ESPN: they come from the sport-tv-guide.live TV listings, so they
     * show what's on and where, with no scores.
     */
    enum class Kind { TEAM, UFC, F1, BOXING, TENNIS, GOLF, LISTINGS, LIVE_NOW }

    /**
     * What the ◀ ▶ control steps through for a sport's fixtures view: days that have fixtures,
     * calendar events (an NFL week, a UFC card, an F1 weekend, a golf tournament), or calendar
     * weeks (boxing). NONE hides it (TV listings show everything coming up).
     */
    enum class Nav { MATCH_DAY, EVENT, WEEK, NONE }

    enum class ViewMode(val label: String) {
        FIXTURES("Fixtures"),
        GAMES("Games"),
        MATCHES("Matches"),
        TABLE("Table"),
        STANDINGS("Standings"),
        CARDS("Fight cards"),
        FIGHTS("Fights"),
        RACES("Race weekends"),
        DRIVERS("Drivers"),
        TEAMS("Constructors"),
        LEADERBOARD("Leaderboard"),
        ON_TV("On TV"),
        LIVE("Live now");

        /** Views that list events (and use the ◀ ▶ navigation) rather than a ranking. */
        val isSchedule: Boolean get() = this in setOf(FIXTURES, GAMES, MATCHES, CARDS, FIGHTS, RACES, LEADERBOARD, ON_TV, LIVE)
    }

    /**
     * The league table's last three columns, which differ by sport: what the "drawn" column
     * holds, the points-difference column, and the points column. Each lists ESPN stat names to
     * try in order.
     */
    data class TableCols(
        val drawnLabel: String = "D",
        val drawnStats: List<String> = listOf("ties", "gamesDrawn"),
        val gdLabel: String = "GD",
        val ptsLabel: String = "PTS",
        val ptsStats: List<String> = listOf("points")
    )

    private val usTable = TableCols("GB", listOf("gamesBehind"), "DIFF", "PCT", listOf("winPercent"))
    private val rugbyTable = TableCols("D", listOf("gamesDrawn", "ties"), "PD")

    /** [espnSport]/[espnLeague] form the ESPN API path (e.g. soccer/eng.1). */
    data class Category(
        val tab: String,
        val name: String,
        val espnSport: String = "",
        val espnLeague: String = "",
        val table: TableCols = TableCols()
    )

    /**
     * [id] is the stable key the "choose sports" setting stores. [listingSlug] is the
     * sport-tv-guide.live section the channel finder (and LISTINGS sports) read.
     */
    data class Sport(
        val id: String,
        val label: String,
        val kind: Kind,
        val nav: Nav,
        val views: List<ViewMode>,
        val categories: List<Category>,
        val listingSlug: String
    )

    val sports = listOf(
        // Everything live across the other chosen sports (TodaySports), then what starts soon.
        Sport(
            "live", "Live Now", Kind.LIVE_NOW, Nav.NONE, listOf(ViewMode.LIVE),
            listOf(Category("LIVE", "Live now")),
            listingSlug = ""
        ),
        Sport(
            "football", "Football", Kind.TEAM, Nav.MATCH_DAY, listOf(ViewMode.FIXTURES, ViewMode.TABLE),
            listOf(
                Category("PL", "Premier League", "soccer", "eng.1"),
                Category("CHAMP", "Championship", "soccer", "eng.2"),
                Category("UCL", "Champions League", "soccer", "uefa.champions"),
                Category("EUROPA", "Europa League", "soccer", "uefa.europa"),
                Category("CONF", "Conference League", "soccer", "uefa.europa.conf"),
                Category("NATIONS", "UEFA Nations League", "soccer", "uefa.nations"),
                Category("FA CUP", "FA Cup", "soccer", "eng.fa"),
                Category("CARABAO", "Carabao Cup", "soccer", "eng.league_cup"),
                Category("LA LIGA", "La Liga", "soccer", "esp.1"),
                Category("SERIE A", "Serie A", "soccer", "ita.1"),
                Category("BUNDESLIGA", "Bundesliga", "soccer", "ger.1"),
                Category("LIGUE 1", "Ligue 1", "soccer", "fra.1"),
                Category("SCOT PREM", "Scottish Premiership", "soccer", "sco.1")
            ),
            listingSlug = "football"
        ),
        Sport(
            "nfl", "NFL", Kind.TEAM, Nav.EVENT, listOf(ViewMode.GAMES, ViewMode.STANDINGS),
            listOf(Category("NFL", "NFL", "football", "nfl",
                TableCols("T", listOf("ties"), "DIFF", "PCT", listOf("winPercent")))),
            listingSlug = "american-football"
        ),
        Sport(
            "ufc", "UFC", Kind.UFC, Nav.EVENT, listOf(ViewMode.CARDS),
            listOf(Category("UFC", "UFC", "mma", "ufc")),
            listingSlug = "mma"
        ),
        Sport(
            "boxing", "Boxing", Kind.BOXING, Nav.WEEK, listOf(ViewMode.FIGHTS),
            listOf(Category("BOXING", "Boxing")),
            listingSlug = "boxing"
        ),
        Sport(
            "f1", "F1", Kind.F1, Nav.EVENT, listOf(ViewMode.RACES, ViewMode.DRIVERS, ViewMode.TEAMS),
            listOf(Category("F1", "Formula 1", "racing", "f1")),
            listingSlug = "formula-1"
        ),
        Sport(
            "rugby", "Rugby", Kind.TEAM, Nav.MATCH_DAY, listOf(ViewMode.FIXTURES, ViewMode.TABLE),
            listOf(
                Category("PREM", "Premiership Rugby", "rugby", "267979", rugbyTable),
                Category("URC", "United Rugby Championship", "rugby", "270557", rugbyTable),
                Category("CHAMPIONS CUP", "Champions Cup", "rugby", "271937", rugbyTable),
                Category("SIX NATIONS", "Six Nations", "rugby", "180659", rugbyTable),
                Category("RUGBY CHAMP", "The Rugby Championship", "rugby", "244293", rugbyTable),
                Category("SUPER RUGBY", "Super Rugby Pacific", "rugby", "242041", rugbyTable),
                Category("WORLD CUP", "Rugby World Cup", "rugby", "164205", rugbyTable),
                Category("NRL", "NRL (rugby league)", "rugby-league", "3", rugbyTable)
            ),
            listingSlug = "rugby"
        ),
        Sport(
            "cricket", "Cricket", Kind.LISTINGS, Nav.NONE, listOf(ViewMode.ON_TV),
            listOf(Category("CRICKET", "Cricket on TV")),
            listingSlug = "cricket"
        ),
        Sport(
            "darts", "Darts", Kind.LISTINGS, Nav.NONE, listOf(ViewMode.ON_TV),
            listOf(Category("DARTS", "Darts on TV")),
            listingSlug = "darts"
        ),
        Sport(
            "snooker", "Snooker", Kind.LISTINGS, Nav.NONE, listOf(ViewMode.ON_TV),
            listOf(Category("SNOOKER", "Snooker on TV")),
            listingSlug = "snooker"
        ),
        Sport(
            "tennis", "Tennis", Kind.TENNIS, Nav.MATCH_DAY, listOf(ViewMode.MATCHES),
            listOf(
                Category("ATP", "ATP Tour", "tennis", "atp"),
                Category("WTA", "WTA Tour", "tennis", "wta")
            ),
            listingSlug = "tennis"
        ),
        Sport(
            "golf", "Golf", Kind.GOLF, Nav.EVENT, listOf(ViewMode.LEADERBOARD),
            listOf(
                Category("PGA", "PGA Tour", "golf", "pga"),
                Category("DP WORLD", "DP World Tour", "golf", "eur"),
                Category("LPGA", "LPGA Tour", "golf", "lpga"),
                Category("LIV", "LIV Golf", "golf", "liv")
            ),
            listingSlug = "golf"
        ),
        Sport(
            "basketball", "Basketball", Kind.TEAM, Nav.MATCH_DAY, listOf(ViewMode.GAMES, ViewMode.STANDINGS),
            listOf(
                Category("NBA", "NBA", "basketball", "nba", usTable),
                Category("WNBA", "WNBA", "basketball", "wnba", usTable)
            ),
            listingSlug = "basketball"
        ),
        Sport(
            "hockey", "Ice Hockey", Kind.TEAM, Nav.MATCH_DAY, listOf(ViewMode.GAMES, ViewMode.STANDINGS),
            listOf(Category("NHL", "NHL", "hockey", "nhl",
                TableCols("OTL", listOf("otLosses", "overtimeLosses"), "DIFF", "PTS", listOf("points")))),
            listingSlug = "ice-hockey"
        ),
        Sport(
            "baseball", "Baseball", Kind.TEAM, Nav.MATCH_DAY, listOf(ViewMode.GAMES, ViewMode.STANDINGS),
            listOf(Category("MLB", "MLB", "baseball", "mlb", usTable)),
            listingSlug = "baseball"
        ),
        Sport(
            "afl", "Aussie Rules", Kind.TEAM, Nav.MATCH_DAY, listOf(ViewMode.FIXTURES),
            listOf(Category("AFL", "AFL", "australian-football", "afl")),
            listingSlug = "australian-football"
        )
    )
}
