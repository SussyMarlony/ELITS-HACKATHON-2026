package ai.patnub.transfer

/** RFC 9285 Base45, as in `contract/contract.ts`. Fits the QR alphanumeric mode. */
object Base45 {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    fun encode(bytes: ByteArray): String = buildString {
        var i = 0
        while (i < bytes.size) {
            if (i + 1 < bytes.size) {
                val n = (bytes[i].toInt() and 0xff) * 256 + (bytes[i + 1].toInt() and 0xff)
                append(ALPHABET[n % 45]).append(ALPHABET[n / 45 % 45]).append(ALPHABET[n / 2025])
            } else {
                val n = bytes[i].toInt() and 0xff
                append(ALPHABET[n % 45]).append(ALPHABET[n / 45])
            }
            i += 2
        }
    }

    /** Throws [IllegalArgumentException] on malformed input. */
    fun decode(text: String): ByteArray {
        val v = text.map { c -> ALPHABET.indexOf(c).also { require(it >= 0) { "bad base45 character" } } }
        require(v.size % 3 != 1) { "bad base45 length" }
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < v.size) {
            if (i + 2 < v.size) {
                val n = v[i] + v[i + 1] * 45 + v[i + 2] * 2025
                require(n <= 0xffff) { "bad base45 value" }
                out.write(n shr 8)
                out.write(n and 0xff)
            } else {
                val n = v[i] + v[i + 1] * 45
                require(n <= 0xff) { "bad base45 value" }
                out.write(n)
            }
            i += 3
        }
        return out.toByteArray()
    }
}
