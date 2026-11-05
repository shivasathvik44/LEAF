package com.leaf.reader.domain.model

/** Explicit input limits keep shelf metadata small without discarding entered text. */
object ShelfLimits {
    const val MAX_NAME_CHARACTERS = 100
    const val MAX_DESCRIPTION_CHARACTERS = 2_000

    fun characters(value: String): Int = value.codePointCount(0, value.length)
    fun validName(value: String): Boolean = value.trim().let { it.isNotEmpty() && characters(it) <= MAX_NAME_CHARACTERS }
    fun validDescription(value: String): Boolean = characters(value) <= MAX_DESCRIPTION_CHARACTERS
    fun validate(name: String, description: String) {
        require(name.trim().isNotEmpty()) { "Give your shelf a name." }
        require(characters(name.trim()) <= MAX_NAME_CHARACTERS) { "Shelf names can contain up to 100 characters. Shorten the name before saving." }
        require(validDescription(description)) { "Shelf descriptions can contain up to 2000 characters. Shorten the description before saving." }
    }
}
