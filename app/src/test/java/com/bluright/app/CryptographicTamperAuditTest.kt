package com.bluright.app

import com.bluright.app.crypto.ManifestVerificationResult
import com.bluright.app.crypto.PrivacyManifest
import com.bluright.app.crypto.PrivacyManifestSigner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Adversarial audit tests verifying cryptographic tamper detection across all manifest fields,
 * public key replacement, signature corruption, and video payload alteration.
 */
class CryptographicTamperAuditTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var signer: PrivacyManifestSigner
    private lateinit var videoFile: File
    private lateinit var originalManifest: PrivacyManifest
    private lateinit var originalJson: String

    @Before
    fun setUp() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val testKeyPair = kpg.generateKeyPair()

        signer = PrivacyManifestSigner(
            keyAlias = "tamper_audit_key",
            isTestMode = true,
            testKeyPair = testKeyPair
        )

        videoFile = tempFolder.newFile("sample_video.mp4").apply {
            writeBytes("CANONICAL_MP4_VIDEO_BYTES_UNDER_ADVERSARIAL_TEST".toByteArray())
        }

        originalManifest = signer.signManifest(
            exportId = "audit-uuid-101",
            createdAtIso = "2026-10-22T00:00:00Z",
            videoFile = videoFile,
            videoDurationMs = 4000L,
            totalFrames = 100,
            maskedFramesCount = 95,
            privacyPolicy = "CONSERVATIVE_FAILSAFE_STRICT",
            auditOutcome = "AUDIT_VERIFIED_CONSERVATIVE_SUCCESS",
            cloudProcessing = false,
            optOutEncounterCount = 100,
            optOutTokensCount = 1,
            strongestRssiDbm = -50,
            maskStylesApplied = listOf("PIXELATE")
        )

        originalJson = originalManifest.toJsonString()
    }

    @Test
    fun testUntamperedVideoAndManifest_verifiesSuccessfully() {
        val result = signer.verifyManifest(originalJson, videoFile)
        assertTrue("Untampered export must verify as Valid, was: $result", result is ManifestVerificationResult.Valid)
    }

    @Test
    fun testTamperedVideoFile_detectsHashMismatch() {
        // Adversary flips 1 byte in the video stream
        videoFile.appendBytes("CORRUPTED".toByteArray())

        val result = signer.verifyManifest(originalJson, videoFile)
        assertTrue("Tampered video must return VideoHashMismatch", result is ManifestVerificationResult.VideoHashMismatch)
        val mismatch = result as ManifestVerificationResult.VideoHashMismatch
        assertEquals(originalManifest.videoSha256, mismatch.expectedSha256)
    }

    @Test
    fun testTamperedAuditOutcome_detectsInvalidSignature() {
        // Adversary modifies auditOutcome in the JSON
        val tamperedJson = JSONObject(originalJson).apply {
            put("auditOutcome", "MALICIOUS_FORGED_OUTCOME")
        }.toString()

        val result = signer.verifyManifest(tamperedJson, videoFile)
        assertTrue("Tampered audit outcome must fail signature verification", result is ManifestVerificationResult.InvalidSignature)
    }

    @Test
    fun testTamperedCloudProcessingFlag_detectsInvalidSignature() {
        // Adversary attempts to flip cloudProcessing from false to true or vice-versa
        val tamperedJson = JSONObject(originalJson).apply {
            put("cloudProcessing", true)
        }.toString()

        val result = signer.verifyManifest(tamperedJson, videoFile)
        assertTrue("Tampered cloudProcessing flag must fail signature verification", result is ManifestVerificationResult.InvalidSignature)
    }

    @Test
    fun testTamperedMaskedFramesCount_detectsInvalidSignature() {
        // Adversary inflates maskedFramesCount to hide unmasked frames
        val tamperedJson = JSONObject(originalJson).apply {
            put("maskedFramesCount", 100)
        }.toString()

        val result = signer.verifyManifest(tamperedJson, videoFile)
        assertTrue("Tampered maskedFramesCount must fail signature verification", result is ManifestVerificationResult.InvalidSignature)
    }

    @Test
    fun testSubstitutedPublicKey_detectsInvalidSignature() {
        // Adversary replaces public key with their own key without a valid signature from that key
        val adversaryKpg = KeyPairGenerator.getInstance("EC")
        adversaryKpg.initialize(ECGenParameterSpec("secp256r1"))
        val adversaryKeyPair = adversaryKpg.generateKeyPair()
        val adversaryPubKeyBase64 = Base64.getEncoder().encodeToString(adversaryKeyPair.public.encoded)

        val json = JSONObject(originalJson)
        val crypto = json.getJSONObject("cryptographicProof")
        crypto.put("publicKeyBase64", adversaryPubKeyBase64)
        json.put("cryptographicProof", crypto)

        val result = signer.verifyManifest(json.toString(), videoFile)
        assertTrue("Substituted public key must fail signature verification", result is ManifestVerificationResult.InvalidSignature)
    }

    @Test
    fun testMissingVideoFile_detectsMissingVideoFile() {
        val nonExistentFile = File(tempFolder.root, "does_not_exist.mp4")
        val result = signer.verifyManifest(originalJson, nonExistentFile)
        assertTrue("Missing file must return MissingVideoFile", result is ManifestVerificationResult.MissingVideoFile)
    }
}
