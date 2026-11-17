package ai.patnub.transfer

import java.util.Base64

/**
 * Public half of the root key that signs clinic licenses (spec v1.2 §4.6.1).
 * shortcut: this is the DEMO root from contract/demo/root_pub.txt; ship the production root
 * before real use. TrustRootTest fails if the two drift apart.
 */
object TrustRoot {
    const val ROOT_PUB_B64U = "_6uMBjYRu_Q4mRUp9TCAoJrePS3DzdqQCO5ecrBErY8"
    val publicKey: ByteArray get() = Base64.getUrlDecoder().decode(ROOT_PUB_B64U)
}
