package com.localdrop

import com.localdrop.crypto.PathSanitizer
import com.localdrop.protocol.FileEntry
import com.localdrop.protocol.FileReceiver
import com.localdrop.protocol.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

class PathAndStreamingTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `chemins hostiles refusés`() {
        listOf("../../etc/passwd", "/system/bin/sh", "a/../../b", "C:\\Windows\\x", "a\u0000b", "..", "dir//file", "")
            .forEach { p -> try { PathSanitizer.sanitizeRelativePath(p); fail("accepté: $p") } catch (_: SecurityException) {} }
    }

    @Test fun `sous-dossiers légitimes préservés`() {
        assertEquals("Photos/2025/été.jpg", PathSanitizer.sanitizeRelativePath("Photos/2025/été.jpg"))
    }

    @Test fun `resolveSafe reste dans la destination`() {
        val f = PathSanitizer.resolveSafe(tmp.root, "a/b/c.txt")
        assertTrue(f.canonicalPath.startsWith(tmp.root.canonicalPath))
    }

    @Test fun `anti-écrasement`() {
        val f = tmp.newFile("photo.jpg")
        assertEquals("photo (1).jpg", PathSanitizer.uniqueFile(f).name)
    }

    private fun receiver(size: Int) =
        FileReceiver(tmp.root, Manifest(listOf(FileEntry(1, "dir/big.bin", size.toLong()))), null) { Long.MAX_VALUE }

    @Test fun `streaming 8 Mo par blocs - fichier identique au bit près et SHA-256 validé`() {
        val data = ByteArray(8 * 1024 * 1024).also { SecureRandom().nextBytes(it) }
        val r = receiver(data.size)
        r.begin(1, 0)
        var off = 0
        while (off < data.size) { val n = minOf(512 * 1024, data.size - off); r.write(1, off.toLong(), data, off, n); off += n }
        val out = r.finish(1, MessageDigest.getInstance("SHA-256").digest(data))
        assertArrayEquals(data, out.readBytes())
        assertFalse(tmp.root.walk().any { it.name.endsWith(".part") })  // plus aucun .part
    }

    @Test fun `hash incorrect - le fichier est supprimé`() {
        val data = ByteArray(1000) { 1 }
        val r = receiver(data.size); r.begin(1, 0); r.write(1, 0, data, 0, data.size)
        try { r.finish(1, ByteArray(32)); fail() } catch (_: IOException) {}
        assertFalse(tmp.root.walk().any { it.isFile })
    }

    @Test fun `dépassement de la taille annoncée refusé`() {
        val r = receiver(100); r.begin(1, 0)
        try { r.write(1, 0, ByteArray(101), 0, 101); fail() } catch (_: SecurityException) {}
    }

    @Test fun `espace insuffisant - transfert refusé avant démarrage`() {
        val r = FileReceiver(tmp.root, Manifest(listOf(FileEntry(1, "x", 1_000_000_000))), null) { 1_000 }
        assertFalse(r.prepare(2).accepted)
    }
}
