package it.sebi.utils

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.Color
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Utility to compute robust fingerprints for files to recognize same content across environments.
 * - Images: 64-bit dHash computed on 9x8 grayscale downscaled image (robust to scaling/compression)
 * - PDFs: SHA-256 of normalized extracted text + page count
 * - Fallback: SHA-256 of raw bytes
 */
object FileFingerprintUtil {

    data class Fingerprint(val value: String, val method: String)

    fun compute(bytes: ByteArray, mime: String?, ext: String?): Fingerprint {
        val lowerExt = ext?.lowercase()?.removePrefix(".")
        val m = mime?.lowercase()
        return try {
            when {
                m?.startsWith("image/") == true && lowerExt != "svg" -> {
                    Fingerprint(dHash64Hex(bytes), "image_dhash64")
                }
                m == "application/pdf" || lowerExt == "pdf" -> {
                    Fingerprint(pdfTextHash(bytes), "pdf_text_sha256")
                }
                else -> {
                    Fingerprint(sha256Hex(bytes), "bytes_sha256")
                }
            }
        } catch (_: Throwable) {
            // Any failure, fallback to raw bytes hash
            Fingerprint(sha256Hex(bytes), "bytes_sha256")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun hammingDistance(h1: String, h2: String): Int {
        val hex1 = h1.takeWhile { it != '-' }
        val hex2 = h2.takeWhile { it != '-' }
        if (hex1.length != hex2.length) return Int.MAX_VALUE
        
        var distance = 0
        for (i in hex1.indices) {
            val v1 = hex1[i].digitToInt(16)
            val v2 = hex2[i].digitToInt(16)
            distance += Integer.bitCount(v1 xor v2)
        }
        return distance
    }

    /**
     * Compute a 256-bit dHash for image bytes and encode as 64-char hex string.
     * Increased resolution (17x16) to reduce collisions on simple images.
     */
    private fun dHash64Hex(bytes: ByteArray): String {
        val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IllegalArgumentException("Invalid image")
        val width = img.width
        val height = img.height
        
        // Scale to 17x16 grayscale
        val scaled = BufferedImage(17, 16, BufferedImage.TYPE_BYTE_GRAY)
        val g = scaled.graphics
        try {
            g.drawImage(img.getScaledInstance(17, 16, Image.SCALE_SMOOTH), 0, 0, 17, 16, Color(0, 0, 0), null)
        } finally {
            g.dispose()
        }
        
        val hashBuilder = StringBuilder()
        for (y in 0 until 16) {
            var rowHash = 0L
            for (x in 0 until 16) { // compare pixel (x) vs (x+1)
                val left = scaled.getRGB(x, y) and 0xFF
                val right = scaled.getRGB(x + 1, y) and 0xFF
                if (left > right) {
                    rowHash = rowHash or (1L shl x)
                }
            }
            hashBuilder.append("%04x".format(rowHash))
        }
        
        // Append original dimensions to fingerprint to further distinguish similar images
        val metadataSuffix = "-${width}x${height}"
        return hashBuilder.toString() + metadataSuffix
    }

    /**
     * Extract text from PDF and return SHA-256 of normalized text + page count.
     */
    private fun pdfTextHash(bytes: ByteArray): String {
        PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
            val stripper = PDFTextStripper()
            val text = stripper.getText(doc)
            val normalized = text.replace("\\s+".toRegex(), " ").trim().lowercase()
            val compound = (doc.numberOfPages.toString() + "\n" + normalized).toByteArray()
            return sha256Hex(compound)
        }
    }
}
