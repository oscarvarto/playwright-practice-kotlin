package oscarvarto.mx.teststats

import java.security.MessageDigest
import java.util.HexFormat

internal object DigestUtils {
    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return HexFormat.of().formatHex(digest.digest(value.toByteArray(Charsets.UTF_8)))
    }
}
