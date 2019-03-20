package com.ciccio.iotcoreclient

import android.content.ContentValues
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.*
import java.security.cert.Certificate
import java.security.spec.InvalidKeySpecException
import javax.security.auth.x500.X500Principal

/**
 * Google Cloud IoT Authentication
 */
private val TAG = MqttAuthentication::class.java.simpleName

class MqttAuthentication {

    private val DEFAULT_KEYSTORE = "AndroidKeyStore"
    private val KEY_ALIAS = "cloudiotauth"
    private val FILE_PUB_KEY = "rs256_x509.pub"

    /** Years Certification Expiration */
    private val CERT_EXPIRATION = 20

    private var certificate : Certificate? = null
    private var privateKey : PrivateKey? = null

    init {
        try {

        } catch (generalSecurityException: GeneralSecurityException) {
            Log.w(TAG, "Security ")
        } catch (ioExceptions: IOException) {
            Log.w(TAG, )
        }
    }

    fun initialize() {

        try {

            val ks = KeyStore.getInstance(DEFAULT_KEYSTORE)
            ks.load(null)

            certificate = ks.getCertificate(KEY_ALIAS)
            if (certificate == null) {
                /** Generate Key */
                Log.d(ContentValues.TAG, "No X509Certificate FOUND (Creating .... ) ")

                generateAuthenticationKey()
                certificate = ks.getCertificate(KEY_ALIAS)
            }
            Log.d(ContentValues.TAG, "Loaded certificate : " + KEY_ALIAS)

            /** Log the Certificate  DEBUG
            if (certificate is X509Certificate) {
            val x509Certificate : X509Certificate = certificate as X509Certificate
            Log.d(TAG, "Subject: " + x509Certificate.subjectX500Principal.toString())
            Log.d(TAG, "Issuer: " + x509Certificate.issuerX500Principal.toString())
            Log.d(TAG, "Signature: " + x509Certificate.signature)
            } */

            val key : Key = ks.getKey(KEY_ALIAS, null)
            privateKey = key as PrivateKey

            exportPublicKey(FILE_PUB_KEY)

        } catch (ioex : GeneralSecurityException) {
            Log.e(ContentValues.TAG, "@EXCEPTION >> Failed to open keystore ", ioex)
        } catch (gsex : IOException) {
            Log.e(ContentValues.TAG, "@EXCEPTION >> General IO Exception ", gsex)
        }

    }

    /**
     * Generate a new RSA Key pair entry in the Android keystore by
     * using the KeyPairGenerator API. This create both a KeyPair and
     * a self-signed certificate, both with the same alias
     */
    @Throws(GeneralSecurityException::class)
    fun generateAuthenticationKey() {
        val kpg : KeyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA, DEFAULT_KEYSTORE)
        kpg.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setCertificateSubject(X500Principal("CN=unused"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build())
        kpg.generateKeyPair()

    }

    /**
     * Exports the authentication certificate to a file
     * @param destination the file to write the certificate to PEM encoded
     */
    @Throws(
        GeneralSecurityException::class,
        IOException::class)
    fun exportPublicKey(fileName: String) {

        /** Write the Public Key in the /key/public.pub  */
        val path = Environment.getExternalStorageDirectory().absolutePath

        val file = File(path + File.separator + fileName)
        val fos = FileOutputStream(file)

        try {
            fos.write(getCertificatePEM().toByteArray())
            fos.flush()
            fos.close()
        } catch (fex : FileNotFoundException) {
            Log.e(ContentValues.TAG, " @EXCEPTION >> ", fex)
        }
    }

    /**
     * Get the Certificate
     */
    fun getCertificate() : Certificate? {
        return certificate
    }

    /**
     * Get the certificate in PEM-format encoded
     */
    fun getCertificatePEM() : String {
        var sb = StringBuilder()
        sb.append("-----BEGIN CERTIFICATE-----\n")
        sb.append(Base64.encodeToString(certificate?.encoded, Base64.DEFAULT))
        sb.append("-----END CERTIFICATE-----\n")
        return sb.toString()
    }

    /**
     * GEt the private Key
     */
    fun getPrivateKey() : PrivateKey? {
        return privateKey
    }

    /**
     * Create the Auth JWT token for the device
     */
    @Throws(
        InvalidKeySpecException::class,
        NoSuchAlgorithmException::class,
        IOException::class)
    fun createJWT(projectId : String) : CharArray {
        val now = DateTime()
        /** Create a JWT token to authenticate this device, the device
         * will be disconnected after the token expires, audience field
         * should always be set to the GCP project id
         */
        val jwtBuilder = Jwts.builder()
            .setIssuedAt(now.toDate())
            .setExpiration(now.plusMinutes(CERT_EXPIRATION).toDate())
            .setAudience(projectId)

        return jwtBuilder.signWith(SignatureAlgorithm.RS256, privateKey).compact().toCharArray()
    }

}