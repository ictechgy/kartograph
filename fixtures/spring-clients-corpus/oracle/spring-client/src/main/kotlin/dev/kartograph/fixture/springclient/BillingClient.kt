package dev.kartograph.fixture.springclient

import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

/** `rootUri` RestTemplate 호출이다. `/`로 시작하는 템플릿에만 root가 붙는다. */
@Component
class BillingClient(private val billingRestTemplate: RestTemplate) {
    fun invoice(id: String): String? = billingRestTemplate.getForObject("/invoices/{id}", String::class.java, id)

    fun replaceInvoice(id: String): String? =
        billingRestTemplate.exchange("/invoices/{id}", HttpMethod.PUT, HttpEntity("body"), String::class.java, id).body

    fun removeInvoice(id: String) = billingRestTemplate.delete("/invoices/{id}", id)

    fun invoiceHeaders() = billingRestTemplate.headForHeaders("/invoices")

    fun createInvoice(): String? = billingRestTemplate.postForObject("/invoices", "body", String::class.java)

    fun external(): String? = billingRestTemplate.getForObject("http://ledger.example.test/entries", String::class.java)
}
