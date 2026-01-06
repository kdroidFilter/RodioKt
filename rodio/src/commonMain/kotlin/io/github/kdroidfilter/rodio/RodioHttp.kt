package io.github.kdroidfilter.rodio

import io.github.kdroidfilter.rodio.native.httpAddRootCertPem
import io.github.kdroidfilter.rodio.native.httpClearRootCerts
import io.github.kdroidfilter.rodio.native.httpSetAllowInvalidCerts

object RodioHttp {
    fun setAllowInvalidCerts(allow: Boolean) {
        httpSetAllowInvalidCerts(allow)
    }

    fun addRootCertPem(pem: String) {
        httpAddRootCertPem(pem)
    }

    fun clearRootCerts() {
        httpClearRootCerts()
    }
}