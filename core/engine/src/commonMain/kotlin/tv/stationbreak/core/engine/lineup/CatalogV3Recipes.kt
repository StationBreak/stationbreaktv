// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.engine.lineup.NameMatcher.Contains
import tv.stationbreak.core.engine.lineup.NameMatcher.Exact
import tv.stationbreak.core.engine.lineup.NameMatcher.Prefix
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.FilterKind
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Source

// The catalog-3 recipes (spec 23): a themed, curated version of every starter channel, with the same key, number,
// band, ceiling and opt-in as the live catalog. They are built here but not yet live: [StarterCatalog] stays at
// version 2 until the catalog swap.
//
// Anchors and theme studios are rules sources, so a kid channel's anchors still pass its kid hold and its ceiling. A
// recipe's anchors are ordered most important first, because the resolver keeps the leading ones that fit the
// per-starter byte budget. The name tables in this file may name franchises, collections, titles, studios and
// networks; nothing else in the repository may.

/** The genre names TMDB uses, in the movie and TV lists. Any other genre spelling becomes a tag source. */
internal val TMDB_GENRES: Set<String> =
    linkedSetOf(
        "Action",
        "Adventure",
        "Animation",
        "Comedy",
        "Crime",
        "Documentary",
        "Drama",
        "Family",
        "Fantasy",
        "History",
        "Horror",
        "Music",
        "Mystery",
        "Romance",
        "Science Fiction",
        "TV Movie",
        "Thriller",
        "War",
        "Western",
        "Action & Adventure",
        "Kids",
        "News",
        "Reality",
        "Sci-Fi & Fantasy",
        "Soap",
        "Talk",
        "War & Politics",
    )

private fun genres(vararg names: String): List<Source> = names.map { Source.Genre(it) }

private fun tags(vararg names: String): List<Source> = names.map { Source.Tag(it) }

private fun years(
    from: Int,
    to: Int,
): List<Source> = listOf(Source.YearRange(from = from, to = to))

private fun collections(vararg matchers: NameMatcher): List<Anchor> = matchers.map { Anchor(AnchorKind.Collection, it) }

private fun titles(vararg matchers: NameMatcher): List<Anchor> = matchers.map { Anchor(AnchorKind.Title, it) }

private fun studios(vararg matchers: NameMatcher): List<Anchor> = matchers.map { Anchor(AnchorKind.Studio, it) }

@Suppress("LongParameterList")
private fun filters(
    genres: List<String>? = null,
    notGenres: List<String>? = null,
    kind: FilterKind? = null,
    maxRuntime: Int? = null,
): Filters =
    Filters(
        genres = genres,
        yearFrom = null,
        yearTo = null,
        minRuntimeMinutes = null,
        maxRuntimeMinutes = maxRuntime,
        kind = kind,
        notGenres = notGenres,
    )

/** The kids' genres every grown-up recipe leaves out (as in catalog version 2). */
private val KIDS_GENRE_EXCLUSIONS: List<Source> = KidHold.KIDS_GENRES.map { Source.Genre(it) }

/** What a grown-up recipe other than an anime channel also leaves out. */
private val ANIME_EXCLUSIONS: List<Source> = listOf(Source.Genre("Anime"), Source.Tag("anime"))

@Suppress("LongParameterList")
private fun recipe(
    band: Band,
    n: Int,
    key: String,
    name: String,
    says: String,
    sources: List<Source>,
    rating: String? = null,
    optIn: Boolean = false,
    anchors: List<Anchor> = emptyList(),
    themeStudios: List<NameMatcher> = emptyList(),
    filters: Filters? = null,
    format: FormatPromise? = null,
    animeOnly: Boolean = false,
): Recipe =
    Recipe(
        key = key,
        name = name,
        personality = says,
        band = band,
        number = n,
        optIn = optIn,
        sources = sources,
        exclusions =
            when {
                band == Band.Kids -> emptyList()
                animeOnly -> KIDS_GENRE_EXCLUSIONS
                else -> KIDS_GENRE_EXCLUSIONS + ANIME_EXCLUSIONS
            },
        maxRating = rating,
        ordering = OrderingMode.Curated,
        collectionHints = emptyList(),
        filters = filters,
        theme = Theme(anchors, themeStudios, format, animeOnly),
    )

private val kidsBand: List<Recipe> =
    listOf(
        recipe(
            Band.Kids,
            n = 4,
            key = "little-farm",
            name = "Little Farm",
            says = "Gentle, slow and sunny, for the very youngest viewers.",
            sources = tags("preschool", "toddler"),
            rating = "TV-Y",
        ),
        recipe(
            Band.Kids,
            n = 8,
            key = "sing-along-station",
            name = "Sing-Along Station",
            says = "Every show breaks into song, and so should you.",
            sources = genres("Music") + tags("singalong", "sing-along", "nursery rhymes", "musical", "kids music"),
            rating = "G",
            anchors = collections(Exact("Musicals"), Exact("The Muppets")),
        ),
        recipe(
            Band.Kids,
            n = 12,
            key = "once-upon",
            name = "Once Upon",
            says = "Fairy tales, folk tales and happily ever after.",
            sources = genres("Fantasy") + tags("fairy tale", "fairy-tale", "folk tale"),
            rating = "PG",
            anchors =
                collections(
                    Contains("Fairy Tales"),
                    Contains("Cinderella"),
                    Contains("Beauty and the Beast"),
                    Contains("Little Mermaid"),
                    Contains("Shrek"),
                    Contains("Peter Pan"),
                    Contains("Alice in Wonderland"),
                ),
        ),
        recipe(
            Band.Kids,
            n = 16,
            key = "saturday-morning",
            name = "Saturday Morning",
            says = "Cartoons and shorts, cereal optional.",
            sources = genres("Animation", "Kids") + tags("cartoon", "shorts", "children"),
            rating = "TV-Y7",
            anchors =
                titles(
                    Contains("Gummi Bears"),
                    Contains("DuckTales"),
                    Contains("Care Bears"),
                    Contains("Smurfs"),
                    Contains("Chip 'n Dale"),
                    Contains("TaleSpin"),
                ) +
                    studios(Contains("Hanna-Barbera")),
        ),
        recipe(
            Band.Kids,
            n = 20,
            key = "backyard-fort",
            name = "Backyard Fort",
            says = "Big-kid adventure: maps, mysteries and no grown-ups allowed.",
            sources = genres("Adventure", "Kids", "Action & Adventure") + tags("adventure", "children"),
            rating = "TV-Y7",
            anchors =
                collections(
                    Exact("Dogs"),
                    Contains("Air Bud"),
                    Contains("Homeward Bound"),
                    Contains("White Fang"),
                    Contains("Old Yeller"),
                ),
        ),
        recipe(
            Band.Kids,
            n = 24,
            key = "critter-channel",
            name = "Critter Channel",
            says = "Dogs, dinosaurs, ducklings and everything in between.",
            sources = tags("animals", "dogs", "dinosaurs", "pets", "nature"),
            rating = "G",
            anchors = collections(Exact("Dinosaurs")),
        ),
        recipe(
            Band.Kids,
            n = 28,
            key = "robot-factory",
            name = "Robot Factory",
            says = "Robots, rockets and things that go beep.",
            sources = genres("Science Fiction", "Sci-Fi & Fantasy", "Kids") + tags("robots", "space", "sci-fi"),
            rating = "TV-Y7",
        ),
        recipe(
            Band.Kids,
            n = 32,
            key = "hey-science",
            name = "Hey, Science!",
            says = "Why is the sky blue? Let's find out.",
            sources = genres("Documentary") + tags("science", "learning", "educational"),
            rating = "TV-Y7",
        ),
        recipe(
            Band.Kids,
            n = 36,
            key = "rainy-day-matinee",
            name = "Rainy Day Matinee",
            says = "Family films for when the weather wins.",
            sources = genres("Family") + tags("family", "family film"),
            rating = "PG",
            anchors =
                collections(
                    Exact("Pixar"),
                    Contains("Walt Disney Animation"),
                    Contains("DreamWorks Animation"),
                    Contains("Toy Story"),
                    Contains("Ice Age"),
                    Contains("Despicable Me"),
                ),
            filters = filters(kind = FilterKind.Movie),
            format = FormatPromise.Films,
        ),
        recipe(
            Band.Kids,
            n = 40,
            key = "little-spirits",
            name = "Little Spirits",
            says = "Family anime: small heroes, big feelings.",
            sources = genres("Anime", "Animation") + tags("anime", "family anime"),
            rating = "PG",
            anchors = collections(Contains("Studio Ghibli")),
        ),
        recipe(
            Band.Kids,
            n = 44,
            key = "lights-out",
            name = "Lights Out",
            says = "Calm, quiet and sleepy. The bedtime channel.",
            sources = tags("bedtime", "calm", "lullaby", "relaxing"),
            rating = "TV-Y",
        ),
        recipe(
            Band.Kids,
            n = 48,
            key = "pocket-heroes",
            name = "Pocket Heroes",
            says = "Capes, masks and kind-hearted saves.",
            sources = tags("superhero", "superheroes", "super hero"),
            rating = "TV-Y7",
        ),
        recipe(
            Band.Kids,
            n = 52,
            key = "flipbook",
            name = "Flipbook",
            says = "Golden-oldie family films and classic cartoons.",
            sources = tags("classic cartoons") + years(from = 1930, to = 1969),
            rating = "G",
        ),
        recipe(
            Band.Kids,
            n = 56,
            key = "snowglobe",
            name = "Snowglobe",
            says = "Winter holidays for small people, sprinkles included.",
            sources = tags("Christmas", "holiday", "winter holiday"),
            rating = "G",
            anchors = collections(Exact("Christmas")),
        ),
        recipe(
            Band.Kids,
            n = 60,
            key = "giggle-garage",
            name = "Giggle Garage",
            says = "Silly shows, funny voices and a lot of pratfalls.",
            sources = genres("Comedy", "Kids") + tags("silly", "slapstick", "children"),
            rating = "TV-Y7",
            anchors =
                collections(
                    Contains("Looney"),
                ) +
                    titles(
                        Contains("Looney"),
                        Contains("Pinky and the Brain"),
                        Contains("Animaniacs"),
                    ),
        ),
        recipe(
            Band.Kids,
            n = 64,
            key = "wheels-and-whistles",
            name = "Wheels & Whistles",
            says = "Trains, trucks, diggers and anything that rolls.",
            sources = tags("trains", "trucks", "cars", "vehicles", "construction"),
            rating = "TV-Y",
        ),
        recipe(
            Band.Kids,
            n = 68,
            key = "small-sleuths",
            name = "Small Sleuths",
            says = "Gentle mysteries, a case in every episode.",
            sources = genres("Mystery") + tags("detective", "mystery"),
            rating = "PG",
            anchors = collections(Contains("Scooby-Doo")) + titles(Contains("Scooby")),
        ),
        recipe(
            Band.Kids,
            n = 72,
            key = "tall-tales-club",
            name = "Tall Tales Club",
            says = "Pirates, explorers and stories that grow in the telling.",
            sources = genres("Adventure") + tags("pirates", "explorers", "treasure"),
            rating = "PG",
            anchors =
                collections(
                    Contains("Narnia"),
                    Contains("NeverEnding"),
                    Contains("Jumanji"),
                    Contains("How to Train Your Dragon"),
                    Contains("Sinbad"),
                ),
        ),
        recipe(
            Band.Kids,
            n = 76,
            key = "spooky-sleepover",
            name = "Spooky Sleepover",
            says = "Friendly ghosts and gentle frights, flashlight under the blanket.",
            sources = tags("Halloween", "ghosts", "spooky", "friendly monsters"),
            rating = "PG",
            anchors = collections(Exact("Halloween")),
        ),
        recipe(
            Band.Kids,
            n = 80,
            key = "tiny-chefs",
            name = "Tiny Chefs",
            says = "Messy kitchens, cooking and baking, small aprons.",
            sources = tags("cooking", "baking", "kids cooking", "food"),
            rating = "G",
        ),
    )

private val generalBand: List<Recipe> =
    listOf(
        recipe(
            Band.General,
            n = 102,
            key = "laugh-track",
            name = "Laugh Track",
            says = "Sitcoms all day, with the laughs built in.",
            sources = genres("Comedy") + tags("sitcom", "situation comedy"),
            filters =
                filters(
                    kind = FilterKind.Episode,
                    maxRuntime = 35,
                    notGenres =
                        TMDB_GENRES.filter {
                            it !in
                                setOf("Comedy", "Family", "Romance")
                        },
                ),
            format = FormatPromise.ShortSeries,
        ),
        recipe(
            Band.General,
            n = 106,
            key = "punchline",
            name = "Punchline",
            says = "Stand-up, sketches and one-liners without the filler.",
            sources = tags("stand-up", "sketch", "comedy special", "sketch comedy"),
        ),
        recipe(
            Band.General,
            n = 110,
            key = "rabbit-ears",
            name = "Rabbit Ears",
            says = "Fuzzy pictures from the fifties and sixties; adjust the antenna.",
            sources = years(from = 1950, to = 1969),
        ),
        recipe(
            Band.General,
            n = 114,
            key = "shag-carpet",
            name = "Shag Carpet",
            says = "The seventies: wide collars, wood panelling, bold choices.",
            sources = years(from = 1970, to = 1979),
        ),
        recipe(
            Band.General,
            n = 118,
            key = "neon-decade",
            name = "Neon Decade",
            says = "Big hair, bigger synths. The eighties, loud and proud.",
            sources = years(from = 1980, to = 1989),
        ),
        recipe(
            Band.General,
            n = 122,
            key = "dial-up",
            name = "Dial-Up",
            says = "The nineties, buffering gently. Please don't pick up the phone.",
            sources = years(from = 1990, to = 1999),
        ),
        recipe(
            Band.General,
            n = 126,
            key = "flip-phone",
            name = "Flip Phone",
            says = "The two-thousands, snapping shut with a satisfying click.",
            sources = years(from = 2000, to = 2009),
        ),
        recipe(
            Band.General,
            n = 130,
            key = "binge-row",
            name = "Binge Row",
            says = "The twenty-tens, when everything became one more episode.",
            sources = years(from = 2010, to = 2019),
        ),
        recipe(
            Band.General,
            n = 134,
            key = "fresh-cut",
            name = "Fresh Cut",
            says = "This decade so far, still warm from the oven.",
            sources = years(from = 2020, to = 2029),
        ),
        recipe(
            Band.General,
            n = 138,
            key = "glass-house",
            name = "Glass House",
            says = "Real people, real drama, a camera in every corner.",
            sources = genres("Reality") + tags("reality", "reality-tv", "reality tv"),
        ),
        recipe(
            Band.General,
            n = 142,
            key = "game-night",
            name = "Game Night",
            says = "Buzzers, big prizes and a host who loves a pun.",
            sources = tags("game show", "quiz", "game-show"),
        ),
        recipe(
            Band.General,
            n = 146,
            key = "couch-talk",
            name = "Couch Talk",
            says = "Hosts, guests and chat from a comfortable seat.",
            sources = genres("Talk") + tags("talk show", "chat show", "late night"),
        ),
        recipe(
            Band.General,
            n = 150,
            key = "matinee-tears",
            name = "Matinee Tears",
            says = "Long-running soaps with secrets, slaps and plot twists.",
            sources = genres("Soap") + tags("soap opera", "telenovela"),
        ),
        recipe(
            Band.General,
            n = 154,
            key = "sideline",
            name = "Sideline",
            says = "Games, highlights and underdog stories from the touchline.",
            sources = tags("sports", "football", "baseball", "basketball", "sport"),
        ),
        recipe(
            Band.General,
            n = 158,
            key = "spoof-central",
            name = "Spoof Central",
            says = "Parodies of everything, played with a perfectly straight face.",
            sources = tags("spoof", "parody", "mockumentary", "satire"),
            anchors = collections(Contains("Spoofs & Parodies"), Contains("Scary Movie"), Contains("Austin Powers")),
        ),
        recipe(
            Band.General,
            n = 162,
            key = "date-night",
            name = "Date Night",
            says = "Romance and rom-coms. Tissues and popcorn recommended.",
            sources = genres("Romance") + tags("romance", "rom-com", "romcom", "romantic comedy", "romantic"),
            anchors = collections(Contains("Valentine's Day"), Contains("Weddings")),
        ),
        recipe(
            Band.General,
            n = 166,
            key = "open-road",
            name = "Open Road",
            says = "Road trips, travel diaries and a very long highway.",
            sources = tags("road trip", "road-trip", "travel", "road movie"),
            anchors = collections(Contains("Road Trips")),
        ),
        recipe(
            Band.General,
            n = 170,
            key = "simmer",
            name = "Simmer",
            says = "Cooking, baking, and the occasional kitchen disaster.",
            sources = tags("cooking", "baking", "food"),
        ),
        recipe(
            Band.General,
            n = 174,
            key = "fixer-hour",
            name = "Fixer Hour",
            says = "Renovations, makeovers and 'how hard can it be?'",
            sources = tags("home improvement", "diy", "renovation", "home & garden", "home and garden"),
        ),
        recipe(
            Band.General,
            n = 178,
            key = "backstage-pass",
            name = "Backstage Pass",
            says = "Concerts, music films and the stories behind the songs.",
            sources = genres("Music") + tags("concert", "live music", "music documentary", "music video"),
        ),
        recipe(
            Band.General,
            n = 182,
            key = "always-december",
            name = "Always December",
            says = "Holiday cheer on every day of the year, snow guaranteed.",
            sources = tags("Christmas", "holiday", "winter holiday"),
            anchors =
                collections(
                    Exact("Christmas"),
                    Contains("Santa Clause"),
                    Contains("Frosty"),
                    Contains("Christmas Chronicles"),
                    Contains("Snow Days"),
                ),
        ),
        recipe(
            Band.General,
            n = 186,
            key = "curtain-call",
            name = "Curtain Call",
            says = "Stage and screen musicals, jazz hands mandatory.",
            sources = tags("musical", "broadway", "stage show"),
            anchors = collections(Exact("Musicals")),
        ),
    )

private val dramaBand: List<Recipe> =
    listOf(
        recipe(
            Band.Drama,
            n = 204,
            key = "prestige",
            name = "Prestige",
            says = "Slow-burn, award-hungry drama. Bring your full attention.",
            sources = genres("Drama") + tags("prestige", "drama series"),
        ),
        recipe(
            Band.Drama,
            n = 208,
            key = "cold-case-cable",
            name = "Cold Case Cable",
            says = "Detectives, mysteries and one unsolved case after another.",
            sources = genres("Crime", "Mystery") + tags("detective", "whodunit", "police procedural"),
        ),
        recipe(
            Band.Drama,
            n = 212,
            key = "order-in-the-court",
            name = "Order in the Court",
            says = "Lawyers, juries and a verdict before the credits.",
            sources = tags("courtroom", "legal drama", "lawyers", "legal"),
        ),
        recipe(
            Band.Drama,
            n = 216,
            key = "code-blue",
            name = "Code Blue",
            says = "Hospital corridors, big emergencies and tired surgeons.",
            sources = tags("hospital", "medical drama", "doctors", "medical"),
        ),
        recipe(
            Band.Drama,
            n = 220,
            key = "silver-screen",
            name = "Silver Screen",
            says = "Old Hollywood in black and white, the glamour intact.",
            sources = tags("classic film", "classic movie") + years(from = 1920, to = 1959),
        ),
        recipe(
            Band.Drama,
            n = 224,
            key = "dead-drop",
            name = "Dead Drop",
            says = "Spies, heists and a briefcase that explodes in ten seconds.",
            sources = genres("Thriller") + tags("spy", "heist", "espionage"),
            anchors =
                collections(
                    Contains("James Bond"),
                    Contains("OSS 117"),
                    Contains("Mission: Impossible"),
                    Contains("Bourne"),
                ) +
                    titles(Exact("Get Smart")),
        ),
        recipe(
            Band.Drama,
            n = 228,
            key = "big-rumble",
            name = "Big Rumble",
            says = "Explosions, car chases and one-liners before the blast.",
            sources = genres("Action", "Action & Adventure") + tags("action", "explosions"),
            anchors =
                collections(
                    Contains("Die Hard"),
                    Contains("Lethal Weapon"),
                    Contains("John Wick"),
                    Contains("Expendables"),
                    Contains("Fast and the Furious"),
                    Contains("Crank"),
                    Contains("Equalizer"),
                    Prefix("Nobody"),
                    Contains("Predator"),
                ),
        ),
        recipe(
            Band.Drama,
            n = 232,
            key = "kung-fu-theater",
            name = "Kung Fu Theater",
            says = "Fists, flying kicks and dubbing that's slightly out of sync.",
            sources = tags("kung fu", "martial arts", "wuxia", "karate"),
            anchors = collections(Contains("Ip Man"), Contains("Once Upon a Time in China")),
            themeStudios =
                listOf(
                    Contains("Shaw Brothers"),
                    Contains("Golden Harvest"),
                    Contains("Film Workshop"),
                    Contains("Mandarin Films"),
                    Contains("Emperor Motion"),
                    Contains("Media Asia"),
                    Contains("Sahamongkol"),
                    Contains("Bona Entertainment"),
                ),
            filters =
                filters(
                    genres = listOf("Action", "Adventure", "Drama", "Fantasy", "History"),
                    notGenres = listOf("Science Fiction", "Sci-Fi"),
                ),
        ),
        recipe(
            Band.Drama,
            n = 236,
            key = "docs-and-dusters",
            name = "Docs & Dusters",
            says = "Documentaries and history, with plenty of archive footage.",
            sources = genres("Documentary", "History") + tags("documentary", "history", "biography"),
        ),
        recipe(
            Band.Drama,
            n = 240,
            key = "front-lines",
            name = "Front Lines",
            says = "War stories, from the trenches to the command tent.",
            sources = genres("War", "War & Politics") + tags("war", "world war", "military"),
            anchors = titles(Prefix("M*A*S*H")) + collections(Contains("Top Gun")),
        ),
        recipe(
            Band.Drama,
            n = 244,
            key = "dusty-trails",
            name = "Dusty Trails",
            says = "Westerns: horses, saloons and a showdown at noon.",
            sources = genres("Western") + tags("western", "cowboys", "spaghetti western"),
            anchors = collections(Exact("Westerns")),
        ),
        recipe(
            Band.Drama,
            n = 248,
            key = "noir-nights",
            name = "Noir Nights",
            says = "Rain-slick streets, trench coats and a narrator who's seen too much.",
            sources = tags("film noir", "noir", "film-noir"),
            anchors = collections(Contains("Film Noir")),
        ),
        recipe(
            Band.Drama,
            n = 252,
            key = "corsets-and-candlelight",
            name = "Corsets & Candlelight",
            says = "Period dramas, grand houses and well-timed glances.",
            sources = tags("period drama", "costume drama", "regency", "historical"),
        ),
        recipe(
            Band.Drama,
            n = 256,
            key = "case-files",
            name = "Case Files",
            says = "True crime, told straight and told slowly.",
            sources = tags("true crime", "true-crime", "cold case"),
        ),
        recipe(
            Band.Drama,
            n = 260,
            key = "life-stories",
            name = "Life Stories",
            says = "Biopics and memoirs about people worth a feature film.",
            sources = tags("biopic", "biography", "based on a true story"),
        ),
        recipe(
            Band.Drama,
            n = 264,
            key = "disaster-zone",
            name = "Disaster Zone",
            says = "Earthquakes, tornadoes, volcanoes, and an ensemble cast in peril.",
            sources = tags("disaster", "natural disaster", "survival"),
        ),
    )

private val genreBand: List<Recipe> =
    listOf(
        recipe(
            Band.Genre,
            n = 304,
            key = "starport",
            name = "Starport",
            says = "Sci-fi: starships, aliens and science that's mostly pretend.",
            sources = genres("Science Fiction", "Sci-Fi & Fantasy") + tags("sci-fi", "science fiction"),
            anchors =
                collections(
                    Contains("Star Trek"),
                    Contains("Star Wars"),
                    Contains("Space Adventures"),
                    Prefix("Dune"),
                ) +
                    titles(Prefix("Star Trek")),
        ),
        recipe(
            Band.Genre,
            n = 308,
            key = "dragons-and-doorways",
            name = "Dragons & Doorways",
            says = "Swords, spells and a quest that begins in a tavern.",
            sources = genres("Fantasy", "Sci-Fi & Fantasy") + tags("fantasy", "sword and sorcery", "swords"),
        ),
        recipe(
            Band.Genre,
            n = 312,
            key = "rubber-suit-theatre",
            name = "Rubber Suit Theatre",
            says = "Giant monsters flattening miniature cities, zip lines visible.",
            sources = tags("kaiju", "giant monsters", "giant monster", "tokusatsu"),
            anchors = collections(Contains("Godzilla"), Contains("Kaiju"), Contains("Gamera"), Exact("Giant Monsters")),
            themeStudios = listOf(Prefix("Toho"), Contains("Daiei"), Contains("Tsuburaya")),
        ),
        recipe(
            Band.Genre,
            n = 316,
            key = "midnight-projector",
            name = "The Midnight Projector",
            says = "Horror and classic monsters, best watched with the lights down.",
            sources = genres("Horror") + tags("horror", "classic monsters", "gothic"),
            anchors =
                collections(
                    Contains("Classic Monsters"),
                    Contains("Hammer Films"),
                    Contains("Evil Dead"),
                    Contains("Blair Witch"),
                ),
        ),
        recipe(
            Band.Genre,
            n = 320,
            key = "the-cheap-seats",
            name = "The Cheap Seats",
            says = "Bad-movie night, with riffing on the soundtrack.",
            sources = tags("bad movie", "riffing", "riffed", "so bad it's good"),
            anchors =
                collections(Exact("Bad Movies")) + titles(Contains("Mystery Science Theater"), Prefix("RiffTrax")) +
                    studios(Contains("RiffTrax")),
        ),
        recipe(
            Band.Genre,
            n = 324,
            key = "drive-in-double",
            name = "Drive-In Double",
            says = "Grindhouse, B-pictures and trailers in the intermission.",
            sources = tags("b-movie", "drive-in", "grindhouse", "exploitation"),
        ),
        recipe(
            Band.Genre,
            n = 328,
            key = "cape-and-cowl",
            name = "Cape & Cowl",
            says = "Superheroes, villains with schemes, and capes in the wind.",
            sources = tags("superhero", "superheroes", "comic book"),
            anchors =
                collections(
                    Contains("Batman"),
                    Contains("Spider-Man"),
                    Contains("Superheroes"),
                    Contains("Marvel Studios"),
                    Contains("Venom"),
                    Contains("Guardians of the Galaxy"),
                ) +
                    titles(Contains("Batman")),
        ),
        recipe(
            Band.Genre,
            n = 332,
            key = "cult-following",
            name = "Cult Following",
            says = "Midnight-screening favourites, quoted word for word.",
            sources = tags("cult", "cult classic", "cult film"),
        ),
        recipe(
            Band.Genre,
            n = 336,
            key = "shuffle-and-groan",
            name = "Shuffle & Groan",
            says = "The undead, the unwashed and the unlucky survivors.",
            sources = tags("zombies", "zombie", "undead"),
        ),
        recipe(
            Band.Genre,
            n = 340,
            key = "jack-o-lantern-lounge",
            name = "Jack-o'-Lantern Lounge",
            says = "Halloween, all year round. Candy corn not included.",
            sources = tags("Halloween", "trick or treat"),
            anchors =
                collections(
                    Contains("Spooky Season"),
                    Exact("Halloween"),
                    Contains("Halloweentown"),
                    Contains("Addams Family"),
                    Contains("Scary Godmother"),
                    Contains("Hotel Transylvania"),
                ),
        ),
        recipe(
            Band.Genre,
            n = 344,
            key = "campfire-tales",
            name = "Campfire Tales",
            says = "Slashers and summer camps. Don't go into the woods.",
            sources = tags("slasher", "summer camp", "slashers"),
        ),
        recipe(
            Band.Genre,
            n = 348,
            key = "paradox",
            name = "Paradox",
            says = "Time travel: yesterday, tomorrow and a very tangled timeline.",
            sources = tags("time travel", "time-travel", "time loop"),
            anchors = collections(Contains("Time Travel")),
        ),
        recipe(
            Band.Genre,
            n = 352,
            key = "neon-rain",
            name = "Neon Rain",
            says = "Cyberpunk and dystopias, glowing in a downpour.",
            sources = tags("cyberpunk", "dystopia", "dystopian", "neo-noir"),
        ),
        recipe(
            Band.Genre,
            n = 356,
            key = "deep-space-drift",
            name = "Deep Space Drift",
            says = "Space operas and lonely spaceships, far from anywhere.",
            sources = tags("space", "space opera", "outer space"),
            anchors =
                titles(Contains("Stargate"), Contains("Babylon 5"), Contains("Farscape"), Contains("Battlestar")) +
                    collections(Contains("Stargate")),
            filters = filters(genres = listOf("Science Fiction", "Sci-Fi & Fantasy")),
        ),
        recipe(
            Band.Genre,
            n = 360,
            key = "creature-feature",
            name = "Creature Feature",
            says = "Something big in the swamp, the deep or the attic.",
            sources = tags("creature feature", "monster movie", "sea monster", "monsters"),
            anchors = collections(Contains("Tremors"), Contains("Critters"), Contains("Jurassic Park")),
            filters = filters(genres = listOf("Horror", "Science Fiction", "Thriller")),
        ),
        recipe(
            Band.Genre,
            n = 364,
            key = "end-times",
            name = "End Times",
            says = "Post-apocalyptic wastelands and the last diner on Earth.",
            sources = tags("post-apocalyptic", "apocalypse", "wasteland", "survival"),
        ),
        recipe(
            Band.Genre,
            n = 368,
            key = "haunted-house",
            name = "Haunted House",
            says = "Ghosts, spirits and noises in the night.",
            sources = tags("ghost", "ghosts", "paranormal", "haunted", "supernatural"),
        ),
        recipe(
            Band.Genre,
            n = 372,
            key = "twist-ending",
            name = "Twist Ending",
            says = "Mind-benders that make you rewatch the first act.",
            sources = tags("twist ending", "mind-bender", "psychological thriller"),
        ),
        recipe(
            Band.Genre,
            n = 376,
            key = "gallows-humor",
            name = "Gallows Humor",
            says = "Dark comedies for people who laugh at the wrong moments.",
            sources = tags("dark comedy", "black comedy", "gallows humor"),
        ),
    )

private val worldBand: List<Recipe> =
    listOf(
        recipe(
            Band.World,
            n = 404,
            key = "tokyo-after-school",
            name = "Tokyo After School",
            says = "Anime of every stripe, from the bell to bedtime.",
            sources = genres("Anime") + tags("anime"),
            animeOnly = true,
        ),
        recipe(
            Band.World,
            n = 408,
            key = "giant-robot-hour",
            name = "Giant Robot Hour",
            says = "Mecha pilots, transforming frames and dramatic launch sequences.",
            sources = tags("mecha", "giant robot", "giant robots"),
            anchors = titles(Contains("Macross"), Contains("Gundam"), Contains("Robotech"), Contains("Evangelion")),
            animeOnly = true,
        ),
        recipe(
            Band.World,
            n = 412,
            key = "power-level",
            name = "Power Level",
            says = "Shonen battles, training arcs and shouted attack names.",
            sources = tags("shonen", "shounen", "battle anime"),
            animeOnly = true,
        ),
        recipe(
            Band.World,
            n = 416,
            key = "tea-time",
            name = "Tea Time",
            says = "British television, brewed strong with a biscuit on the side.",
            sources = tags("british", "british tv", "uk", "british comedy"),
            anchors = collections(Exact("British")),
            themeStudios =
                listOf(
                    Prefix("BBC"),
                    Prefix("ITV"),
                    Contains("Channel 4"),
                    Prefix("Sky One"),
                    Prefix("Sky 1"),
                    Prefix("Sky Atlantic"),
                    Contains("U&Dave"),
                    Contains("Thames"),
                    Contains("Granada"),
                    Contains("Yorkshire Television"),
                    Contains("London Weekend"),
                ),
        ),
        recipe(
            Band.World,
            n = 420,
            key = "whodunit-isles",
            name = "Whodunit Isles",
            says = "Village murders, cosy inspectors and vicars with secrets.",
            sources = tags("cozy mystery", "cosy mystery", "british mystery", "village mystery"),
        ),
        recipe(
            Band.World,
            n = 424,
            key = "continental-drift",
            name = "Continental Drift",
            says = "World cinema with subtitles; read along and travel.",
            sources = tags("foreign", "world cinema", "subtitled", "international"),
        ),
        recipe(
            Band.World,
            n = 428,
            key = "seoul-sundays",
            name = "Seoul Sundays",
            says = "Korean dramas: slow burns, big hearts, sudden twists.",
            sources = tags("k-drama", "kdrama", "korean drama", "korean"),
        ),
        recipe(
            Band.World,
            n = 432,
            key = "masala-hour",
            name = "Masala Hour",
            says = "Song, dance and drama, Bollywood style.",
            sources = tags("bollywood", "hindi", "indian cinema"),
        ),
        recipe(
            Band.World,
            n = 436,
            key = "telenovela-tonight",
            name = "Telenovela Tonight",
            says = "Passion, betrayal and cliffhangers every night.",
            sources = tags("telenovela", "latin drama", "spanish drama"),
        ),
        recipe(
            Band.World,
            n = 440,
            key = "cherry-blossom-hour",
            name = "Cherry Blossom Hour",
            says = "Slice-of-life anime: quiet days, good tea and falling petals.",
            sources = tags("slice of life", "slice-of-life", "iyashikei"),
            animeOnly = true,
        ),
    )

private val premiumBand: List<Recipe> =
    listOf(
        recipe(
            Band.Premium,
            n = 504,
            key = "after-hours",
            name = "After Hours",
            says = "Adults only, and only if you ask for it.",
            sources = tags("adult", "erotic", "18+", "erotica"),
            optIn = true,
        ),
        recipe(
            Band.Premium,
            n = 508,
            key = "graveyard-shift",
            name = "Graveyard Shift",
            says = "Late-night oddities for insomniacs and night owls.",
            sources = tags("late night", "midnight movie", "insomnia"),
        ),
        recipe(
            Band.Premium,
            n = 512,
            key = "one-season-only",
            name = "One Season Only",
            says = "Miniseries and limited runs, told in order and gone too soon.",
            sources = tags("miniseries", "mini-series", "limited series"),
        ),
        recipe(
            Band.Premium,
            n = 516,
            key = "cartoons-after-dark",
            name = "Cartoons After Dark",
            says = "Animation made for adults, with grown-up language.",
            sources = tags("adult animation", "adult cartoon", "animated sitcom"),
            anchors =
                studios(Contains("Adult Swim"), Contains("Comedy Central")) +
                    collections(Contains("Aqua Teen"), Contains("Heavy Metal")),
            themeStudios =
                listOf(
                    Contains("Adult Swim"),
                    Contains("Comedy Central"),
                    Exact("FX"),
                    Exact("FXX"),
                    Prefix("FX Productions"),
                ),
            filters = filters(genres = listOf("Animation")),
        ),
        recipe(
            Band.Premium,
            n = 520,
            key = "family-business",
            name = "Family Business",
            says = "Mobsters, made men and dinners that end badly.",
            sources = tags("mafia", "gangster", "mob", "organized crime"),
        ),
        recipe(
            Band.Premium,
            n = 524,
            key = "anthology-hour",
            name = "Anthology Hour",
            says = "A new story every week, a new twist every time.",
            sources = tags("anthology", "anthology series"),
        ),
        recipe(
            Band.Premium,
            n = 528,
            key = "satire-desk",
            name = "Comedy County",
            says = "Comedy with a twist of something else.",
            sources = genres("Comedy"),
            filters =
                filters(
                    genres =
                        listOf(
                            "Crime",
                            "Action",
                            "Mystery",
                            "Fantasy",
                            "Science Fiction",
                            "Music",
                            "Action & Adventure",
                            "Sci-Fi & Fantasy",
                        ),
                    kind = FilterKind.Episode,
                    maxRuntime = 35,
                    notGenres = listOf("Animation"),
                ),
            format = FormatPromise.ShortSeries,
        ),
        recipe(
            Band.Premium,
            n = 532,
            key = "directors-chair",
            name = "Director's Chair",
            says = "Director's cuts, commentary and the making of it all.",
            sources = tags("director's cut", "making of", "behind the scenes", "extended cut"),
        ),
    )

/**
 * The catalog-3 recipes, in catalog order. Declared last: top-level properties are initialised in file order, and this
 * reads the bands above.
 */
internal val CATALOG_V3_RECIPES: List<Recipe> =
    kidsBand + generalBand + dramaBand + genreBand + worldBand + premiumBand
