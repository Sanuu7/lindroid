package dev.lindroid.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ArchiveSafetyTest {
    @Test
    fun normalizesSafeImagePaths() {
        assertEquals("usr/bin/bash", ArchiveSafety.safeRelativePath("./usr/bin/bash"))
        assertEquals("etc/hosts", ArchiveSafety.safeRelativePath("/etc//hosts"))
        assertNull(ArchiveSafety.safeRelativePath("./"))
    }

    @Test
    fun blocksPathTraversal() {
        assertThrows(IOException::class.java) {
            ArchiveSafety.safeRelativePath("usr/../../data/escape")
        }
    }
}
