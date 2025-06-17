package it.sebi.utils

import java.io.InputStream
import java.security.MessageDigest


fun calculateMD5Hash(input: String): String = calculateMD5Hash(input.byteInputStream())

fun calculateMD5Hash(inputStream: InputStream): String {
    val digest = MessageDigest.getInstance("MD5")
    val buffer = ByteArray(8192)
    var read: Int

    while (inputStream.read(buffer).also { read = it } > 0) {
        digest.update(buffer, 0, read)
    }

    return digest.digest().joinToString("") { "%02x".format(it) }
}