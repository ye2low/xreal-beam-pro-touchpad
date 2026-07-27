// Copyright 2026 Paul Gratz
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.pgratz.artouchpad.adb

import android.content.Context
import android.os.Build
import android.sun.security.provider.X509Factory
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import java.util.Random

// Identity this app presents to adbd. adbd remembers the public key in
// /data/misc/adb/adb_keys once the user has paired with it, and that file survives reboots
// — which is what makes pairing a one-time step and every later connection silent.
//
// The key lives in device-protected storage so it can be read before the user unlocks the
// phone; credential-encrypted storage would be unreadable at that point.
class AdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        api = Build.VERSION.SDK_INT
        val dir = context.filesDir
        val keyFile = File(dir, "adb_private.key")
        val certFile = File(dir, "adb_cert.pem")

        if (keyFile.exists() && certFile.exists()) {
            privateKey = java.security.KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            certificate = certFile.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
        } else {
            val generator = KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
            }
            val pair = generator.generateKeyPair()
            privateKey = pair.private
            certificate = selfSign(pair.public, pair.private)
            keyFile.writeBytes(privateKey.encoded)
            certFile.writeText(pemEncode(certificate))
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    // Shown in the "Wireless debugging" paired-devices list on the phone.
    override fun getDeviceName(): String = "AR Touchpad"

    companion object {
        // Twenty years. A short-lived certificate would sail through pairing and then fail
        // the TLS handshake weeks later, with the paired key still sitting in adb_keys —
        // a failure that would look like the pairing had been forgotten.
        private const val VALIDITY_MS = 20L * 365 * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: AdbConnectionManager? = null

        // The private key has to outlive any single Activity, and adbd tolerates only one
        // connection per key at a time, so the manager is a process-wide singleton.
        fun getInstance(context: Context): AdbConnectionManager =
            instance ?: synchronized(this) {
                instance ?: AdbConnectionManager(
                    context.applicationContext.createDeviceProtectedStorageContext()
                ).also { instance = it }
            }

        private fun selfSign(publicKey: java.security.PublicKey, privateKey: PrivateKey): Certificate {
            val algorithm = "SHA512withRSA"
            val notBefore = Date()
            val notAfter = Date(System.currentTimeMillis() + VALIDITY_MS)
            val name = X500Name("CN=AR Touchpad")
            val extensions = CertificateExtensions().apply {
                set(
                    "SubjectKeyIdentifier",
                    SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier),
                )
                set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
            }
            val info = X509CertInfo().apply {
                set("version", CertificateVersion(2))
                set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
                set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
                set("subject", CertificateSubjectName(name))
                set("key", CertificateX509Key(publicKey))
                set("validity", CertificateValidity(notBefore, notAfter))
                set("issuer", CertificateIssuerName(name))
                set("extensions", extensions)
            }
            return X509CertImpl(info).apply { sign(privateKey, algorithm) }
        }

        private fun pemEncode(certificate: Certificate): String = buildString {
            append(X509Factory.BEGIN_CERT).append('\n')
            append(
                Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
                    .encodeToString(certificate.encoded)
            )
            append('\n').append(X509Factory.END_CERT)
        }
    }
}
