package lv.jolkins.pixelorchestrator.runtimeinstaller

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class RuntimeManifestVerifier {

  fun verifySha256WithEcdsa(
    manifestBytes: ByteArray,
    signatureBase64: String,
    publicKeyPem: String
  ): Boolean {
    return runCatching {
      val signatureBytes = Base64.getDecoder().decode(signatureBase64.trim())
      val publicKey = decodePublicKeyPem(publicKeyPem)
      val verifier = Signature.getInstance("SHA256withECDSA")
      verifier.initVerify(publicKey)
      verifier.update(manifestBytes)
      verifier.verify(signatureBytes)
    }.getOrDefault(false)
  }

  private fun decodePublicKeyPem(publicKeyPem: String): PublicKey {
    val body = publicKeyPem
      .replace("-----BEGIN PUBLIC KEY-----", "")
      .replace("-----END PUBLIC KEY-----", "")
      .replace("\\s".toRegex(), "")
      .trim()
    val bytes = Base64.getDecoder().decode(body)
    val spec = X509EncodedKeySpec(bytes)
    return KeyFactory.getInstance("EC").generatePublic(spec)
  }
}
