package com.eds.overlay.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [EdsRepository] data decoding and validation.
 */
class EdsRepositoryTest {

    @Test
    fun `decodeAssetPayload - plain json string decodes unchanged`() {
        val rawJson = """[{"lat":41.0,"lng":29.0,"direction":180.0,"speedLimit":50}]"""
        val bytes = rawJson.toByteArray(Charsets.UTF_8)
        val result = EdsRepository.decodeAssetPayload(bytes)
        assertEquals(rawJson, result)
    }

    @Test
    fun `decodeAssetPayload - xor obfuscated bytes decode correctly`() {
        val expected = """[{"lat":41.0082,"lng":28.9784}]"""
        val expectedBytes = expected.toByteArray(Charsets.UTF_8)
        val key = byteArrayOf(
            0x4D, 0x75, 0x61, 0x76, 0x69, 0x6E, 0x45, 0x44, 0x53, 0x32, 0x30, 0x32, 0x36
        )
        val obfuscated = ByteArray(expectedBytes.size) { i ->
            (expectedBytes[i].toInt() xor key[i % key.size].toInt()).toByte()
        }

        val decoded = EdsRepository.decodeAssetPayload(obfuscated)
        assertEquals(expected, decoded)
    }

    @Test
    fun `decodeAssetPayload - empty bytes returns empty json array`() {
        val decoded = EdsRepository.decodeAssetPayload(ByteArray(0))
        assertEquals("[]", decoded)
    }
}
