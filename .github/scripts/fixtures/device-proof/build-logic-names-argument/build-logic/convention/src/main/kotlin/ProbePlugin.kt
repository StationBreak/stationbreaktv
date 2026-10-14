package x
fun configure(args: MutableMap<String, String>) {
    args["serverAddress"] = System.getenv("MEDIA_URL") ?: ""
}
