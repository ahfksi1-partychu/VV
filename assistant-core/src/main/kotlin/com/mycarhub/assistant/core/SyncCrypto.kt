package com.mycarhub.assistant.core

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Drive To-Do 동기화 암호화 (기기 안에서만, 서버는 암호문만 본다). 설계: docs/DRIVE_SYNC.md.
 *
 * - 페어링: 차량·휴대폰이 각각 임시 EC P-256 키를 만들어 공개키만 서버를 통해 교환하고, ECDH 공유 비밀 +
 *   6자리 페어링 코드로 데이터 키를 만든다 (HKDF-SHA256). 키 자체는 서버로 가지 않는다.
 *   서버가 공개키를 바꿔치기하면 두 화면의 6자리 확인 번호([PairingKeys.verifyCode])가 달라진다.
 * - 레코드: AES-256-GCM. 종류·id·수정 시각·삭제 여부를 추가 인증 데이터로 묶어, 서버가 이 값을 바꾸면 복호화가 실패한다.
 */
object SyncCrypto {

    private const val CURVE = "secp256r1"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val INFO_DATA = "mycarhub-drive-sync-v1:data"
    private const val INFO_VERIFY = "mycarhub-drive-sync-v1:verify"

    private val random = SecureRandom()
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    class PairingKeys(val dataKey: ByteArray, val verifyCode: String)

    data class Sealed(val nonce: String, val cipherText: String)

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE), random) }.generateKeyPair()

    fun publicKey(pair: KeyPair): String = b64.encodeToString(pair.public.encoded)

    /**
     * 페어링 키 계산. 양쪽이 같은 [carPublic]·[phonePublic]·[code]를 넣으면 같은 결과가 나온다.
     * [myPrivate]는 내 임시 비밀키, [peerPublic]은 상대 공개키(Base64 X.509).
     */
    fun derive(myPrivate: PrivateKey, peerPublic: String, code: String, carPublic: String, phonePublic: String): PairingKeys {
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64.decode(peerPublic)))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(myPrivate)
            doPhase(peer, true)
            generateSecret()
        }
        val transcript = "$carPublic|$phonePublic"
        val prk = hmac(code.toByteArray(Charsets.UTF_8), shared)
        shared.fill(0)
        val dataKey = expand(prk, "$INFO_DATA|$transcript", 32)
        val verify = expand(prk, "$INFO_VERIFY|$transcript", 4)
        prk.fill(0)
        val number = ((verify[0].toLong() and 0xff) shl 24 or ((verify[1].toLong() and 0xff) shl 16) or
            ((verify[2].toLong() and 0xff) shl 8) or (verify[3].toLong() and 0xff)) % 1_000_000
        return PairingKeys(dataKey, number.toString().padStart(6, '0'))
    }

    fun seal(key: ByteArray, meta: SyncMeta, kind: String, plain: ByteArray): Sealed {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad(kind, meta))
        return Sealed(b64.encodeToString(nonce), b64.encodeToString(cipher.doFinal(plain)))
    }

    /** 키가 다르거나 암호문·메타데이터가 바뀌었으면 null (그 레코드는 적용하지 않는다). */
    fun open(key: ByteArray, meta: SyncMeta, kind: String, sealed: Sealed): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, unb64.decode(sealed.nonce)))
        cipher.updateAAD(aad(kind, meta))
        cipher.doFinal(unb64.decode(sealed.cipherText))
    } catch (e: AEADBadTagException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun aad(kind: String, meta: SyncMeta) =
        "$kind|${meta.id}|${meta.updatedAt}|${meta.deleted}|${meta.origin}".toByteArray(Charsets.UTF_8)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** HKDF-Expand (RFC 5869) */
    private fun expand(prk: ByteArray, info: String, length: Int): ByteArray {
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            previous = hmac(prk, previous + info.toByteArray(Charsets.UTF_8) + byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - written)
            System.arraycopy(previous, 0, out, written, n)
            written += n
            counter++
        }
        return out
    }
}
