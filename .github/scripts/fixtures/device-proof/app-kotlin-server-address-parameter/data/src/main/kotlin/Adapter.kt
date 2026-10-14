class JellyfinAdapter(private val serverAddress: String) {
    fun base() = serverAddress.trimEnd('/')
}
