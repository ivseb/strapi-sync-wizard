package utils

import org.bouncycastle.crypto.digests.SHAKEDigest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

fun initBouncyCastle() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        Security.addProvider(BouncyCastleProvider())
    }
}

data class ShortenParams(
    val name: String,
    val len: Int = 50,
    val minTokenLength: Int = 3,
    val hashLength: Int = 5,
    val hashSeparator: String = ""
)

fun shortenName(params: ShortenParams): String {
    // Validation phase - calcola la lunghezza disponibile
    val availableLength = params.len - params.hashLength - params.hashSeparator.length

    // Se il nome è già abbastanza corto, ritornalo così com'è
    if (params.name.length <= params.len) {
        return params.name
    }

    // Altrimenti, tronca e aggiungi hash
    val truncatedName = params.name.substring(0, availableLength)
    val hash = generateShake256Hash(params.name, params.hashLength)

    return truncatedName + params.hashSeparator + hash
}

private fun generateShake256Hash(input: String, length: Int): String {
    val shake = SHAKEDigest(256)
    val inputBytes = input.toByteArray(Charsets.UTF_8)

    shake.update(inputBytes, 0, inputBytes.size)

    // SHAKE256 può produrre output di qualsiasi lunghezza
    // Per ottenere 'length' caratteri hex, abbiamo bisogno di length/2 byte
    val outputBytes = ByteArray((length + 1) / 2)
    shake.doFinal(outputBytes, 0, outputBytes.size)

    // Converti in hex
    val hexString = outputBytes.joinToString("") { "%02x".format(it) }
    return hexString.substring(0, length)
}
// Funzione di utilità per il caso specifico della query
fun shortenTableName(name: String): String {
    return shortenName(
        ShortenParams(
            name = name,
            len = 50,
            minTokenLength = 3,
            hashLength = 5,
            hashSeparator = ""
        )
    )
}

fun main() {

    val tableName = "components_redemption_unitlink_redemption_unitlink_amps"
    val result = shortenTableName(tableName)

    println("Input: $tableName")
    println("Output: $result")
    println("Expected: components_redemption_unitlink_redemption_uni4858f")
    println("Match: ${result == "components_redemption_unitlink_redemption_uni4858f"}")
}