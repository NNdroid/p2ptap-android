package app.fjj.p2ptap.config

// Multiaddrs contain no literal whitespace. Accept the separators used by
// copied address lists, including non-breaking spaces from rich text.
private val peerAddressSeparators = Regex("[\\s\\p{Z},;]+")

fun splitPeerAddresses(text: String): List<String> = splitPeerAddresses(listOf(text))

fun splitPeerAddresses(entries: Iterable<String>): List<String> = entries
    .flatMap { it.split(peerAddressSeparators) }
    .filter { it.isNotBlank() }
    .distinct()
