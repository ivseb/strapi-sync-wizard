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

    /**
     * Compute a 64-bit dHash for image bytes and encode as 16-char hex string.
     */
    private fun dHash64Hex(bytes: ByteArray): String {
        val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IllegalArgumentException("Invalid image")
        // Scale to 9x8 grayscale
        val scaled = BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY)
        val g = scaled.graphics
        try {
            g.drawImage(img.getScaledInstance(9, 8, Image.SCALE_SMOOTH), 0, 0, 9, 8, Color(0, 0, 0), null)
        } finally {
            g.dispose()
        }
        var hash = 0UL
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) { // compare pixel (x) vs (x+1)
                val left = scaled.getRGB(x, y) and 0xFF
                val right = scaled.getRGB(x + 1, y) and 0xFF
                if (left > right) {
                    hash = hash or (1UL shl bit)
                }
                bit++
            }
        }
        return hash.toString(16).padStart(16, '0')
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
