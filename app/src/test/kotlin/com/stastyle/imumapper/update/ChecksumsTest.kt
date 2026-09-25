package com.stastyle.imumapper.update

import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ChecksumsTest {

    @Test
    fun matchesKnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Checksums.sha256Hex(ByteArrayInputStream(ByteArray(0))),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Checksums.sha256Hex(ByteArrayInputStream("abc".toByteArray())),
        )
    }

    @Test
    fun streamsFilesLargerThanTheBuffer() {
        val file = File.createTempFile("checksums", ".bin")
        try {
            // 200 000 bytes of 'a' crosses the 64 KiB buffer boundary several times.
            file.writeBytes(ByteArray(200_000) { 'a'.code.toByte() })
            val expected = Checksums.sha256Hex(ByteArrayInputStream(file.readBytes()))
            assertEquals(expected, Checksums.sha256Hex(file))
            assertEquals(64, expected.length)
        } finally {
            file.delete()
        }
    }
}
