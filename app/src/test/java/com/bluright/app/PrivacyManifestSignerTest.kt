package com.bluright.app

import com.bluright.app.crypto.ManifestVerificationResult
import com.bluright.app.crypto.PrivacyManifest
import com.bluright.app.crypto.PrivacyManifestSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class PrivacyManifestSignerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var signer: PrivacyManifestSigner
    private lateinit var dummyVideoFile: File

    @Before
    fun setUp() {
        // Use standard JCA EC key generator for pure JVM test environment
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val testKeyPair = kpg.generateKeyPair()

        signer = PrivacyManifestSigner(
            keyAlias = "test_manifest_key",
            isTestMode = true,
            testKeyPair = testKeyPair
        )

        dummyVideoFile = tempFolder.newFile("sample_export.mp4").apply {
            writeBytes("DUMMY_MP4_VIDEO_STREAM_BYTES_FOR_TESTING_1234567890".toByteArray())
        }
    }

    @Test
    fun testSignAndVerifyValidManifest_succeeds() {
        val manifest = signer.signManifest(
            exportId = "export-uuid-1234",
            createdAtIso = "2026-10-21T23:30:00Z",
            videoFile = dummyVideoFile,
            videoDurationMs = 5000L,
            totalFrames = 120,
            maskedFramesCount = 120,
            privacyPolicy = "CONSERVATIVE_FAILSAFE_STRICT",
            optOutEncounterCount = 1,
            optOutTokensCount = 1,
            strongestRssiDbm = -58,
            maskStylesApplied = listOf("PIXELATE")
        )

        assertNotNull("Signature must not be empty", manifest.signatureBase64)
        assertTrue("Signature should be base64", manifest.signatureBase64.isNotEmpty())

        val jsonString = manifest.toJsonString()
        val result = signer.verifyManifest(jsonString, dummyVideoFile)

        assertTrue("Verification must be Valid, was: $result", result is ManifestVerificationResult.Valid)
        val valid = result as ManifestVerificationResult.Valid
        assertEquals("export-uuid-1234", valid.exportId)
        assertEquals(manifest.videoSha256, valid.videoSha256)
        assertEquals(120, valid.totalFrames)
        assertEquals(120, valid.maskedFramesCount)
    }

    @Test
    fun testTamperedVideoFile_failsWithHashMismatch() {
        val manifest = signer.signManifest(
            exportId = "export-uuid-5678",
            createdAtIso = "2026-10-21T23:30:00Z",
            videoFile = dummyVideoFile,
            videoDurationMs = 3000L,
            totalFrames = 72,
            maskedFramesCount = 72
        )

        // Tamper with video file after signing (flip 1 byte)
        dummyVideoFile.appendBytes("TAMPERED_POST_SIGNING".toByteArray())

        val result = signer.verifyManifest(manifest.toJsonString(), dummyVideoFile)

        assertTrue(
            "Tampered video file must trigger VideoHashMismatch, was: $result",
            result is ManifestVerificationResult.VideoHashMismatch
        )
        val mismatch = result as ManifestVerificationResult.VideoHashMismatch
        assertEquals(manifest.videoSha256, mismatch.expectedSha256)
    }

    @Test
    fun testTamperedManifestContent_failsWithInvalidSignature() {
        val manifest = signer.signManifest(
            exportId = "export-uuid-9999",
            createdAtIso = "2026-10-21T23:30:00Z",
            videoFile = dummyVideoFile,
            videoDurationMs = 4000L,
            totalFrames = 96,
            maskedFramesCount = 96
        )

        // Tamper with manifest JSON (attacker tries to claim zero faces were masked)
        val tamperedJson = manifest.toJsonString().replace(
            "\"maskedFramesCount\": 96",
            "\"maskedFramesCount\": 0"
        )

        val result = signer.verifyManifest(tamperedJson, dummyVideoFile)

        assertTrue(
            "Tampered manifest payload must fail signature verification, was: $result",
            result is ManifestVerificationResult.InvalidSignature
        )
    }

    @Test
    fun testMissingVideoFile_failsWithMissingVideoFile() {
        val manifest = signer.signManifest(
            exportId = "export-uuid-0000",
            createdAtIso = "2026-10-21T23:30:00Z",
            videoFile = dummyVideoFile,
            videoDurationMs = 1000L,
            totalFrames = 24,
            maskedFramesCount = 24
        )

        val nonExistentFile = File(tempFolder.root, "does_not_exist.mp4")
        val result = signer.verifyManifest(manifest.toJsonString(), nonExistentFile)

        assertTrue(
            "Missing video file must trigger MissingVideoFile result, was: $result",
            result is ManifestVerificationResult.MissingVideoFile
        )
    }

    @Test
    fun testMalformedManifestJson_failsWithMalformedManifest() {
        val result = signer.verifyManifest("{ NOT_VALID_JSON :::: }", dummyVideoFile)
        assertTrue(
            "Corrupt JSON must return MalformedManifest, was: $result",
            result is ManifestVerificationResult.MalformedManifest
        )
    }

    @Test
    fun testDeterministicCanonicalPayload() {
        val manifest1 = PrivacyManifest(
            manifestVersion = "1.0",
            exportId = "test-id",
            createdAtIso = "2026-10-21T23:00:00Z",
            videoFileName = "video.mp4",
            videoSha256 = "abc123def456",
            videoSizeBytes = 1024L,
            videoDurationMs = 2000L,
            totalFrames = 48,
            maskedFramesCount = 48,
            privacyPolicy = "CONSERVATIVE_FAILSAFE_STRICT",
            optOutEncounterCount = 1,
            optOutTokensCount = 1,
            strongestRssiDbm = -65,
            maskStylesApplied = listOf("PIXELATE", "FROSTED"),
            publicKeyBase64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA",
            signatureBase64 = "SIG_BASE64_1"
        )

        val manifest2 = manifest1.copy(
            signatureBase64 = "SIG_BASE64_2" // Different signature
        )

        // Canonical payload excludes the signature and must be strictly identical
        assertEquals(manifest1.toCanonicalPayload(), manifest2.toCanonicalPayload())
    }

    @Test
    fun testRealDeviceExportManifestVerification_succeeds() {
        val manifestJson = """
{
  "manifestVersion": "1.0",
  "exportId": "b1c294b7-b366-4c2a-b6f7-7e2359cf4762",
  "createdAtIso": "2026-10-22T00:08:15Z",
  "videoFileName": "bluright_20261022_000810.mp4",
  "videoSha256": "e2b232e5c304e75f2ef613b147efafde4579378e7c8622de1e563891d2799f80",
  "videoSizeBytes": 1223056,
  "videoDurationMs": 4389,
  "totalFrames": 81,
  "maskedFramesCount": 5,
  "privacyPolicy": "CONSERVATIVE_FAILSAFE_STRICT",
  "auditOutcome": "AUDIT_VERIFIED_STRENGTHENED_COVERAGE",
  "cloudProcessing": false,
  "limitationsDisclaimer": "BlazeFace is an on-device heuristic vision model; cryptographic signature proves audit integrity, not absolute biometric re-identification impossibility.",
  "optOutSummary": {
    "optOutEncounterCount": 0,
    "optOutTokensCount": 0,
    "strongestRssiDbm": null
  },
  "maskStylesApplied": [
    "PIXELATE"
  ],
  "cryptographicProof": {
    "algorithm": "SHA256withECDSA",
    "keyAlias": "bluright_privacy_manifest_key_v1",
    "publicKeyBase64": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELo3ort9Aw2ri4mk+mm1L+DxNpsEKA3WRAjQR7feyVh+rI3D//28NaKO1uuObk6TjB3UDvnUEIEKExLxjf7hP5A==",
    "signatureBase64": "MEYCIQCsVgloWbpJip63ny+BnZWT4Q4NinRkolgfqWuQdwv4kgIhAKqDNxVJuWe0OAs9YgEBjVHGekxRU8xwMgqGj/MS9AvR"
  }
}
        """.trimIndent()

        val manifest = PrivacyManifest.fromJsonString(manifestJson)
        val canonicalPayload = manifest.toCanonicalPayload()

        val keyBytes = java.util.Base64.getDecoder().decode(manifest.publicKeyBase64)
        val keySpec = java.security.spec.X509EncodedKeySpec(keyBytes)
        val kf = java.security.KeyFactory.getInstance("EC")
        val publicKey = kf.generatePublic(keySpec)

        val sig = java.security.Signature.getInstance("SHA256withECDSA")
        sig.initVerify(publicKey)
        sig.update(canonicalPayload.toByteArray(Charsets.UTF_8))
        val sigBytes = java.util.Base64.getDecoder().decode(manifest.signatureBase64)
        val isValid = sig.verify(sigBytes)

        assertTrue("Cryptographic signature from physical Android Keystore must verify!", isValid)
    }

    @Test
    fun testRealDeviceFailClosedManifestVerification_succeeds() {
        val manifestJson = """
{
  "manifestVersion": "1.0",
  "exportId": "59701818-3b6e-497d-bec5-60d8f926bd42",
  "createdAtIso": "2026-10-22T00:49:45Z",
  "videoFileName": "bluright_20261022_004943.mp4",
  "videoSha256": "1f52d0272256fbb59114934ecaa17afd814aa5933997b907085b853459cae804",
  "videoSizeBytes": 84268,
  "videoDurationMs": 1619,
  "totalFrames": 26,
  "maskedFramesCount": 1,
  "blackedOutFramesCount": 25,
  "unmaskedFramesCount": 0,
  "discardedFramesCount": 0,
  "privacyPolicy": "CONSERVATIVE_FAILSAFE_FAILCLOSED_STRICT",
  "auditOutcome": "AUDIT_VERIFIED_CONSERVATIVE_FAILSAFE",
  "cloudProcessing": false,
  "limitationsDisclaimer": "BlazeFace is an on-device heuristic vision model; cryptographic signature proves audit integrity, not absolute biometric re-identification impossibility.",
  "optOutSummary": {
    "optOutEncounterCount": 26,
    "optOutTokensCount": 2,
    "strongestRssiDbm": -53
  },
  "maskStylesApplied": [
    "PIXELATE"
  ],
  "cryptographicProof": {
    "algorithm": "SHA256withECDSA",
    "keyAlias": "bluright_privacy_manifest_key_v1",
    "publicKeyBase64": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELo3ort9Aw2ri4mk+mm1L+DxNpsEKA3WRAjQR7feyVh+rI3D//28NaKO1uuObk6TjB3UDvnUEIEKExLxjf7hP5A==",
    "signatureBase64": "MEQCIBWbXuo4TKwP9sLO14rRIJVA47xpDkDco2j2sqlRY8QiAiAj3i5iM/fdwMrp0U+FatpD0qHY7sde5Ut0o4VMu3KiLQ=="
  }
}
        """.trimIndent()

        val manifest = PrivacyManifest.fromJsonString(manifestJson)
        val canonicalPayload = manifest.toCanonicalPayload()

        val keyBytes = java.util.Base64.getDecoder().decode(manifest.publicKeyBase64)
        val keySpec = java.security.spec.X509EncodedKeySpec(keyBytes)
        val kf = java.security.KeyFactory.getInstance("EC")
        val publicKey = kf.generatePublic(keySpec)

        val sig = java.security.Signature.getInstance("SHA256withECDSA")
        sig.initVerify(publicKey)
        sig.update(canonicalPayload.toByteArray(Charsets.UTF_8))
        val sigBytes = java.util.Base64.getDecoder().decode(manifest.signatureBase64)
        val isValid = sig.verify(sigBytes)

        assertTrue("Cryptographic signature from physical Android Keystore on fail-closed manifest must verify!", isValid)
    }
}
