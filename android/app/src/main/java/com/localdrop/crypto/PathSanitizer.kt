package com.localdrop.crypto

import java.io.File

/** Défense anti « Path Traversal » : TOUT chemin reçu du réseau est hostile par défaut. */
object PathSanitizer {
    private const val MAX_SEGMENT = 200
    private const val MAX_TOTAL = 1024
    private val FORBIDDEN = Regex("[<>:\"|?*\\\\]")

    /** Retourne un chemin relatif normalisé (séparateur `/`) ou lève [SecurityException]. */
    fun sanitizeRelativePath(raw: String): String {
        if (raw.isBlank() || raw.length > MAX_TOTAL) throw SecurityException("chemin vide ou trop long")
        if (raw.any { it.code < 0x20 || it.code == 0x7F }) throw SecurityException("caractère de contrôle")
        if (raw.startsWith("/") || raw.startsWith("\\") || Regex("^[A-Za-z]:").containsMatchIn(raw))
            throw SecurityException("chemin absolu interdit")

        val segments = raw.replace('\\', '/').split('/')
        val clean = segments.map { seg ->
            val s = seg.trim().trimEnd('.', ' ')
            if (seg.isEmpty() || seg == "." || seg == ".." || s.isEmpty()) throw SecurityException("segment interdit")
            if (s.length > MAX_SEGMENT) throw SecurityException("nom trop long")
            s.replace(FORBIDDEN, "_")
        }
        return clean.joinToString("/")
    }

    /** Résout puis vérifie via le chemin canonique que la cible reste dans [destinationDir]. */
    fun resolveSafe(destinationDir: File, relativePath: String): File {
        val rel = sanitizeRelativePath(relativePath)
        val root = destinationDir.canonicalFile
        val target = File(root, rel).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) throw SecurityException("évasion du dossier de destination")
        return target
    }

    /** Anti-écrasement : `photo.jpg` → `photo (1).jpg` → `photo (2).jpg` … */
    fun uniqueFile(target: File): File {
        if (!target.exists()) return target
        val name = target.name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        var i = 1
        while (true) {
            val candidate = File(target.parentFile, "$base ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }
}
