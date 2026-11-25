package local.familytv

enum class ChannelGroup(val label: String) {
    BANGLADESH("Bangladeshi"),
    KIDS("Kids"),
    NEWS("News"),
    SPORTS("Sports"),
    ENTERTAINMENT("Entertainment"),
    MOVIES("Movies"),
    DOCUMENTARY("Documentary"),
    ALL("All channels");

    companion object {
        private val bangladeshi = setOf(
            "atnbangla", "atnnews", "banglatv", "banglavision", "boishakhitv", "btv",
            "channel24", "channel9", "channeli", "dbcnews", "deeptotv", "deshtv", "ekattortv",
            "etv", "gazitv", "gtvhd", "independenttv", "jamunatv", "maasranga", "nagoriktv",
            "news24", "ntv", "rtv", "satv", "somoytv", "tsports", "tsports1",
        )

        fun classify(title: String, provider: Set<String>): Set<ChannelGroup> {
            val name = titleKey(title).replace(" ", "")
            return buildSet {
                if (name in bangladeshi) add(BANGLADESH)
                if (listOf("cartoon", "pogo", "nick", "disney", "boomerang", "kids").any(name::contains)) add(KIDS)
                if (listOf("news", "somoy", "jamuna", "ekattor", "channel24", "independent").any(name::contains)) add(NEWS)
                if ("sports" in provider || listOf("sports", "sonyten", "cricket").any(name::contains)) add(SPORTS)
                if ("movies" in provider || listOf("cinema", "movies", "sonypix", "stargold").any(name::contains)) add(MOVIES)
                if (listOf("discovery", "nationalgeographic", "animalplanet", "tlc", "history").any(name::contains)) add(DOCUMENTARY)
                if ("entertainment" in provider || isEmpty()) add(ENTERTAINMENT)
            }
        }
    }
}
