package lv.jolkins.pixelorchestrator.runtimeinstaller

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class RuntimeManifestVerifierTest {

  private val verifier = RuntimeManifestVerifier()

  @Test
  fun acceptsValidSignature() {
    val keyPair = generateKeyPair()
    val manifest = """{"schema":1,"artifacts":[]}""".toByteArray()
    val signature = sign(keyPair.private.encoded, manifest)
    val publicPem = toPublicPem(keyPair.public.encoded)

    assertTrue(verifier.verifySha256WithEcdsa(manifest, signature, publicPem))
  }

  @Test
  fun rejectsTamperedManifest() {
    val keyPair = generateKeyPair()
    val manifest = """{"schema":1,"artifacts":[]}""".toByteArray()
    val signature = sign(keyPair.private.encoded, manifest)
    val tampered = """{"schema":2,"artifacts":[]}""".toByteArray()
    val publicPem = toPublicPem(keyPair.public.encoded)

    assertFalse(verifier.verifySha256WithEcdsa(tampered, signature, publicPem))
  }

  @Test
  fun rejectsWrongSignature() {
    val keyPair = generateKeyPair()
    val manifest = """{"schema":1,"artifacts":[]}""".toByteArray()
    val publicPem = toPublicPem(keyPair.public.encoded)
    val badSignature = Base64.getEncoder().encodeToString(ByteArray(64))

    assertFalse(verifier.verifySha256WithEcdsa(manifest, badSignature, publicPem))
  }

  private fun generateKeyPair() = KeyPairGenerator.getInstance("EC").run {
    initialize(256)
    generateKeyPair()
  }

  private fun sign(privatePkcs8: ByteArray, message: ByteArray): String {
    val privateKey = java.security.KeyFactory.getInstance("EC")
      .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privatePkcs8))
    val signature = Signature.getInstance("SHA256withECDSA")
    signature.initSign(privateKey)
    signature.update(message)
    return Base64.getEncoder().encodeToString(signature.sign())
  }

  private fun toPublicPem(publicEncoded: ByteArray): String {
    val body = Base64.getEncoder().encodeToString(publicEncoded)
    return buildString {
      appendLine("-----BEGIN PUBLIC KEY-----")
      body.chunked(64).forEach { appendLine(it) }
      appendLine("-----END PUBLIC KEY-----")
    }
  }
}
