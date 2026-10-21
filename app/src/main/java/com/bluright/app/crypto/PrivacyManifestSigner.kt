package com.bluright.app.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Manages asymmetric cryptographic signing and verification of Bluright Privacy Manifests.
 *
 * Uses hardware-backed Android KeyStore (ECDSA secp256r1) on Android devices,
 * with standard Java Cryptography Architecture (JCA) fallback for local JVM unit testing.
 */
class PrivacyManifestSigner(
    val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val isTestMode: Boolean = false,
    private val testKeyPair: KeyPair? = null
) {
    private val algorithm = "SHA256withECDSA"

    /**
     * Retrieves or generates the EC key pair used to sign privacy manifests.
     */
    fun getKeyPair(): KeyPair {
        if (isTestMode && testKeyPair != null) {
            return testKeyPair
        }

        return try {
            getOrGenerateAndroidKeyStoreKeyPair()
        } catch (e: Exception) {
            Log.w(TAG, "AndroidKeyStore unavailable, falling back to standard JCA: ${e.message}")
            generateStandardEcKeyPair()
        }
    }

    private fun getOrGenerateAndroidKeyStoreKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER)
        keyStore.load(null)

        if (keyStore.containsAlias(keyAlias)) {
            val privateKey = keyStore.getKey(keyAlias, null) as? PrivateKey
            val certificate = keyStore.getCertificate(keyAlias)
            val publicKey = certificate?.publicKey

            if (privateKey != null && publicKey != null) {
                return KeyPair(publicKey, privateKey)
            }
        }

        // Generate new EC Key in Android KeyStore
        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE_PROVIDER
        )

        val parameterSpec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()

        kpg.initialize(parameterSpec)
        return kpg.generateKeyPair()
    }

    private fun generateStandardEcKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    /**
     * Signs the manifest payload and returns a fully populated, signed PrivacyManifest.
     */
    fun signManifest(
        exportId: String,
        createdAtIso: String,
        videoFile: File,
        videoDurationMs: Long,
        totalFrames: Int,
        maskedFramesCount: Int,
        blackedOutFramesCount: Int = 0,
        unmaskedFramesCount: Int = 0,
        discardedFramesCount: Int = 0,
        privacyPolicy: String = "CONSERVATIVE_FAILSAFE_STRICT",
        auditOutcome: String = "AUDIT_VERIFIED_CONSERVATIVE_SUCCESS",
        cloudProcessing: Boolean = false,
        optOutEncounterCount: Int = 0,
        optOutTokensCount: Int = 0,
        strongestRssiDbm: Int? = null,
        maskStylesApplied: List<String> = listOf("CYBER_MOSAIC"),
        limitationsDisclaimer: String = "BlazeFace is an on-device heuristic vision model; cryptographic signature proves audit integrity, not absolute biometric re-identification impossibility."
    ): PrivacyManifest {
        val videoSha256 = calculateFileSha256(videoFile)
        val videoSizeBytes = videoFile.length()
        val keyPair = getKeyPair()

        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)

        // Build unsigned proto-manifest to generate deterministic payload
        val unsigned = PrivacyManifest(
            manifestVersion = "1.0",
            exportId = exportId,
            createdAtIso = createdAtIso,
            videoFileName = videoFile.name,
            videoSha256 = videoSha256,
            videoSizeBytes = videoSizeBytes,
            videoDurationMs = videoDurationMs,
            totalFrames = totalFrames,
            maskedFramesCount = maskedFramesCount,
            blackedOutFramesCount = blackedOutFramesCount,
            unmaskedFramesCount = unmaskedFramesCount,
            discardedFramesCount = discardedFramesCount,
            privacyPolicy = privacyPolicy,
            auditOutcome = auditOutcome,
            cloudProcessing = cloudProcessing,
            optOutEncounterCount = optOutEncounterCount,
            optOutTokensCount = optOutTokensCount,
            strongestRssiDbm = strongestRssiDbm,
            maskStylesApplied = maskStylesApplied,
            limitationsDisclaimer = limitationsDisclaimer,
            signingAlgorithm = algorithm,
            keyAlias = keyAlias,
            publicKeyBase64 = publicKeyBase64,
            signatureBase64 = ""
        )

        val canonicalPayload = unsigned.toCanonicalPayload()

        val signer = Signature.getInstance(algorithm)
        signer.initSign(keyPair.private)
        signer.update(canonicalPayload.toByteArray(Charsets.UTF_8))
        val signatureBytes = signer.sign()
        val signatureBase64 = Base64.getEncoder().encodeToString(signatureBytes)

        return unsigned.copy(signatureBase64 = signatureBase64)
    }

    /**
     * Cryptographically verifies a PrivacyManifest against an associated MP4 video file.
     */
    fun verifyManifest(manifestJson: String, videoFile: File): ManifestVerificationResult {
        if (!videoFile.exists()) {
            return ManifestVerificationResult.MissingVideoFile(videoFile.absolutePath)
        }

        val manifest = try {
            PrivacyManifest.fromJsonString(manifestJson)
        } catch (e: Exception) {
            return ManifestVerificationResult.MalformedManifest(e.message ?: "Invalid JSON")
        }

        // 1. Verify Video SHA-256 Digest
        val actualVideoSha256 = calculateFileSha256(videoFile)
        if (!actualVideoSha256.equals(manifest.videoSha256, ignoreCase = true)) {
            return ManifestVerificationResult.VideoHashMismatch(
                expectedSha256 = manifest.videoSha256,
                actualSha256 = actualVideoSha256
            )
        }

        // 2. Decode Public Key
        val publicKey: PublicKey = try {
            val keyBytes = Base64.getDecoder().decode(manifest.publicKeyBase64)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("EC")
            keyFactory.generatePublic(keySpec)
        } catch (e: Exception) {
            return ManifestVerificationResult.InvalidSignature("Failed to decode public key: ${e.message}")
        }

        // 3. Verify Signature over Canonical Manifest Payload
        return try {
            val signatureBytes = Base64.getDecoder().decode(manifest.signatureBase64)
            val verifier = Signature.getInstance(manifest.signingAlgorithm)
            verifier.initVerify(publicKey)
            verifier.update(manifest.toCanonicalPayload().toByteArray(Charsets.UTF_8))
            val isValid = verifier.verify(signatureBytes)

            if (isValid) {
                ManifestVerificationResult.Valid(
                    exportId = manifest.exportId,
                    videoFileName = manifest.videoFileName,
                    videoSha256 = manifest.videoSha256,
                    totalFrames = manifest.totalFrames,
                    maskedFramesCount = manifest.maskedFramesCount,
                    signingAlgorithm = manifest.signingAlgorithm
                )
            } else {
                ManifestVerificationResult.InvalidSignature()
            }
        } catch (e: Exception) {
            ManifestVerificationResult.InvalidSignature("Verification error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "PrivacyManifestSigner"
        const val DEFAULT_KEY_ALIAS = "bluright_privacy_manifest_key_v1"
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"

        fun calculateFileSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            FileInputStream(file).use { fis ->
                var bytesRead = fis.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = fis.read(buffer)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
