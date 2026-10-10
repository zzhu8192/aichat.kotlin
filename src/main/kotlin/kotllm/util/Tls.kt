package kotllm.util

import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** An SSL context that trusts what the *machine* trusts, not just what the JDK
  * shipped with.
  *
  * Corporate networks routinely terminate TLS at a proxy and re-sign every
  * certificate with a private CA. The OS trusts it; the JVM does not, because it
  * carries its own `cacerts`. Some hosts then work and others fail with
  *
  *     PKIX path building failed: unable to find valid certification path
  *
  * which reads like a bug in kotllm and is not -- found exactly that way here,
  * where `api.github.com` was not intercepted and worked while DuckDuckGo,
  * Wikipedia, Maven Central and Stack Exchange all failed.
  *
  * So the trust manager is a union: the JDK store, the Windows root store when
  * available, and anything named by `KOTLLM_TRUSTSTORE`. A chain any member
  * accepts is accepted, which is the question a browser answers. Nothing is
  * disabled -- a certificate nobody vouches for is still refused.
  */
object Tls {

  /** Where to look for an explicit bundle: a PEM file, or a JKS/PKCS12
    * keystore. Useful on Linux and macOS, where there is no equivalent of the
    * Windows root store to read. */
  private val explicitStore: String?
    get() =
      System.getenv("KOTLLM_TRUSTSTORE")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: System.getProperty("kotllm.truststore")
          ?.trim()
          ?.takeIf { it.isNotEmpty() }

  /** True when the union found more trust anchors than the JDK alone, which is
    * the interesting case to report in `/info`. */
  val augmented: Boolean by lazy { managers.size > 1 }

  val describe: String by lazy {
    if (!augmented) "jdk cacerts"
    else "jdk cacerts + ${managers.size - 1} system/explicit store(s)"
  }

  val context: SSLContext by lazy {
    try {
      val ctx = SSLContext.getInstance("TLS")
      ctx.init(null, arrayOf<TrustManager>(UnionTrustManager(managers)), null)
      ctx
    } catch (e: Exception) {
      // A broken union is worse than no union: fall back to whatever the JVM
      // would have done on its own rather than failing every request.
      SSLContext.getDefault()
    }
  }

  private val managers: List<X509TrustManager> by lazy {
    (listOf(fromKeyStore(null)) + listOf(windowsRoot, fromExplicit)).mapNotNull { it }
  }

  private fun fromKeyStore(store: KeyStore?): X509TrustManager? =
    try {
      val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      // null means "the JDK default store", which is exactly what we want as
      // the first member of the union.
      factory.init(store)
      factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    } catch (e: Exception) {
      null
    }

  /** The Windows certificate store, which is where a corporate CA lands when
    * IT installs it. Absent on every other platform, hence the quiet null. */
  private val windowsRoot: X509TrustManager? by lazy {
    try {
      val ks = KeyStore.getInstance("Windows-ROOT")
      ks.load(null, null)
      fromKeyStore(ks)
    } catch (e: Exception) {
      null
    }
  }

  private val fromExplicit: X509TrustManager? get() =
    explicitStore?.let { path ->
      try {
        val file = Paths.get(path)
        when {
          !Files.isRegularFile(file) -> null
          path.lowercase().endsWith(".pem") || path.lowercase().endsWith(".crt") ||
              path.lowercase().endsWith(".cer") ->
            pemStore(file)?.let { ks -> fromKeyStore(ks) }
          else -> {
            val ks = KeyStore.getInstance(if (path.lowercase().endsWith(".p12")) "PKCS12" else "JKS")
            val input = Files.newInputStream(file)
            try {
              ks.load(input, System.getenv("KOTLLM_TRUSTSTORE_PASSWORD")?.toCharArray() ?: "changeit".toCharArray())
            } finally {
              input.close()
            }
            fromKeyStore(ks)
          }
        }
      } catch (e: Exception) {
        null
      }
    }

  /** A PEM bundle may hold several certificates; all of them are added. */
  private fun pemStore(file: java.nio.file.Path): KeyStore? =
    try {
      val factory = CertificateFactory.getInstance("X.509")
      val input = Files.newInputStream(file)
      val certs =
        try {
          factory.generateCertificates(input)
        } finally {
          input.close()
        }

      val ks = KeyStore.getInstance(KeyStore.getDefaultType())
      ks.load(null, null)
      var i = 0
      certs.forEach { cert ->
        ks.setCertificateEntry("kotllm-$i", cert)
        i += 1
      }
      if (i == 0) null else ks
    } catch (e: Exception) {
      null
    }

  /** Accepts a chain that any member store accepts.
    *
    * Deliberately not "trust everything": the last failure is rethrown, so a
    * certificate nobody vouches for is still refused and the error still says
    * why.
    */
  private class UnionTrustManager(private val delegates: List<X509TrustManager>) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
      check { m -> m.checkClientTrusted(chain, authType) }
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
      check { m -> m.checkServerTrusted(chain, authType) }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
      delegates.flatMap { it.acceptedIssuers.toList() }.toTypedArray()

    private fun check(f: (X509TrustManager) -> Unit) {
      var last: Exception? = null
      val accepted = delegates.any { m ->
        try {
          f(m)
          true
        } catch (e: Exception) {
          last = e
          false
        }
      }
      if (!accepted) {
        last?.let { e ->
          when (e) {
            is java.security.cert.CertificateException -> throw e
            else -> throw java.security.cert.CertificateException(e.message, e)
          }
        }
      }
    }
  }

  /** Turns the JDK's opaque trust failure into something actionable.
    *
    * Left as a helper rather than applied at the throw site because the same
    * advice applies to the chat transport, retrieval and fetching, and each
    * reports errors its own way.
    */
  fun explain(message: String?): String? =
    if (message == null || !(message.contains("PKIX") || message.contains("certification path") ||
        message.contains("certificate_unknown")))
      null
    else
      "the TLS certificate could not be verified, which on a corporate network usually means " +
        "a proxy is re-signing traffic with a private CA that the JVM does not trust. kotllm " +
        "already trusts the Windows certificate store; if this machine is not Windows, point " +
        "KOTLLM_TRUSTSTORE at the CA bundle (a .pem or .jks)."
}
