package io.github.davydgky.launcher.index

/**
 * German names of the labels ML Kit's on-device model gives pictures, for the common ones; the rest
 * stay findable by their English name.
 */
object ImageLabelNames {

    fun german(label: String): String? = GERMAN[label.lowercase()]

    private val GERMAN = mapOf(
        // Animals
        "dog" to "Hund", "cat" to "Katze", "bird" to "Vogel", "horse" to "Pferd", "fish" to "Fisch", "insect" to "Insekt",
        "butterfly" to "Schmetterling", "pet" to "Haustier", "duck" to "Ente", "cattle" to "Rind", "bear" to "Bär",
        "lion" to "Löwe", "elephant" to "Elefant", "rabbit" to "Kaninchen", "squirrel" to "Eichhörnchen",
        // Nature
        "flower" to "Blume", "plant" to "Pflanze", "tree" to "Baum", "forest" to "Wald", "grass" to "Gras",
        "garden" to "Garten", "sky" to "Himmel", "cloud" to "Wolke", "sunset" to "Sonnenuntergang", "beach" to "Strand",
        "lake" to "See", "river" to "Fluss", "waterfall" to "Wasserfall", "mountain" to "Berg", "snow" to "Schnee",
        "ice" to "Eis", "desert" to "Wüste", "rock" to "Fels", "sand" to "Sand", "rainbow" to "Regenbogen",
        "moon" to "Mond", "leaf" to "Blatt", "field" to "Feld", "park" to "Park", "cave" to "Höhle", "storm" to "Sturm",
        "sea" to "Meer", "ocean" to "Ozean", "wave" to "Welle", "waves" to "Wellen", "canyon" to "Schlucht",
        // Places and buildings
        "building" to "Gebäude", "skyscraper" to "Wolkenkratzer", "bridge" to "Brücke", "church" to "Kirche",
        "tower" to "Turm", "castle" to "Burg", "city" to "Stadt", "road" to "Straße", "room" to "Zimmer",
        "kitchen" to "Küche", "bathroom" to "Badezimmer", "bedroom" to "Schlafzimmer", "stadium" to "Stadion",
        "museum" to "Museum", "temple" to "Tempel", "monument" to "Denkmal", "playground" to "Spielplatz",
        "swimming pool" to "Schwimmbad", "pool" to "Pool", "restaurant" to "Restaurant", "shop" to "Laden",
        "market" to "Markt", "airport" to "Flughafen", "harbor" to "Hafen", "dam" to "Staudamm", "fountain" to "Brunnen",
        // Vehicles
        "car" to "Auto", "bicycle" to "Fahrrad", "motorcycle" to "Motorrad", "bus" to "Bus", "train" to "Zug",
        "boat" to "Boot", "airplane" to "Flugzeug", "vehicle" to "Fahrzeug", "wheel" to "Rad", "truck" to "Lkw",
        "ship" to "Schiff", "helicopter" to "Hubschrauber",
        // Food and drink
        "food" to "Essen", "cake" to "Kuchen", "pizza" to "Pizza", "bread" to "Brot", "fruit" to "Obst",
        "vegetable" to "Gemüse", "coffee" to "Kaffee", "wine" to "Wein", "beer" to "Bier", "cocktail" to "Cocktail",
        "dessert" to "Nachtisch", "cookie" to "Keks", "pasta" to "Nudeln", "sushi" to "Sushi", "hamburger" to "Burger",
        "salad" to "Salat", "soup" to "Suppe", "cheese" to "Käse", "egg" to "Ei", "chocolate" to "Schokolade",
        "ice cream" to "Eis", "cup" to "Tasse", "drink" to "Getränk", "meal" to "Mahlzeit", "candy" to "Süßigkeiten",
        "juice" to "Saft", "tableware" to "Geschirr",
        // People
        "baby" to "Baby", "smile" to "Lächeln", "selfie" to "Selfie", "hand" to "Hand", "hair" to "Haare",
        "beard" to "Bart", "glasses" to "Brille", "sunglasses" to "Sonnenbrille", "hat" to "Hut", "shoe" to "Schuh",
        "jacket" to "Jacke", "dress" to "Kleid", "jeans" to "Jeans", "team" to "Team", "crowd" to "Menschenmenge",
        "child" to "Kind", "eyelash" to "Wimper", "tattoo" to "Tattoo", "muscle" to "Muskel", "skin" to "Haut",
        // Events and activities
        "party" to "Party", "christmas" to "Weihnachten", "fireworks" to "Feuerwerk", "concert" to "Konzert",
        "wedding" to "Hochzeit", "birthday" to "Geburtstag", "dance" to "Tanz", "soccer" to "Fußball",
        "swimming" to "Schwimmen", "surfing" to "Surfen", "skiing" to "Skifahren", "camping" to "Camping",
        "tent" to "Zelt", "bonfire" to "Lagerfeuer", "running" to "Laufen", "cycling" to "Radfahren",
        "bowling" to "Bowling", "sports" to "Sport", "game" to "Spiel", "fun" to "Spaß", "vacation" to "Urlaub",
        "halloween" to "Halloween", "easter egg" to "Osterei", "graduation" to "Abschluss",
        // Things
        "toy" to "Spielzeug", "book" to "Buch", "paper" to "Papier", "receipt" to "Kassenbon", "computer" to "Computer",
        "mobile phone" to "Handy", "television" to "Fernseher", "musical instrument" to "Musikinstrument",
        "guitar" to "Gitarre", "piano" to "Klavier", "couch" to "Sofa", "bed" to "Bett", "chair" to "Stuhl",
        "table" to "Tisch", "lamp" to "Lampe", "flag" to "Flagge", "map" to "Karte", "clock" to "Uhr",
        "jewelry" to "Schmuck", "bag" to "Tasche", "shelf" to "Regal", "screenshot" to "Bildschirmfoto",
        "poster" to "Plakat", "painting" to "Gemälde", "sculpture" to "Skulptur", "money" to "Geld",
        "bottle" to "Flasche", "balloon" to "Luftballon", "umbrella" to "Regenschirm", "candle" to "Kerze",
        "flowerpot" to "Blumentopf", "curtain" to "Vorhang", "window" to "Fenster", "door" to "Tür",
        "stairs" to "Treppe", "light" to "Licht", "night" to "Nacht",
        "watch" to "Armbanduhr", "camera" to "Kamera", "headphones" to "Kopfhörer", "comics" to "Comics",
        "cushion" to "Kissen", "blanket" to "Decke", "rug" to "Teppich", "plush" to "Plüschtier",
    )
}
