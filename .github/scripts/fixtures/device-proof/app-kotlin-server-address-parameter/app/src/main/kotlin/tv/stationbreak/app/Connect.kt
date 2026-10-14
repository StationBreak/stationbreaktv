package tv.stationbreak.app

class ServerAddress(val value: String)

fun connect(serverAddress: ServerAddress) = serverAddress.value
