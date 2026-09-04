package dev.kartograph.fixture

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class MoshiEnvelope(val item: Item) {
    @JsonClass(generateAdapter = true)
    data class Item(val value: String)
}
