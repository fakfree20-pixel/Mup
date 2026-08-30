fun minifySdp(sdp: String): String {
    return sdp.lines().filter { line ->
        val trimmed = line.trim()
        !trimmed.startsWith("a=extmap:") && !trimmed.startsWith("a=rtcp-fb:")
    }.joinToString("\r\n")
}
