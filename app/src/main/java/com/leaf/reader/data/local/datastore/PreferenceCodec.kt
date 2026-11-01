package com.leaf.reader.data.local.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.leaf.reader.domain.model.LeafPreferences
import com.leaf.reader.domain.model.ReaderPreferences

internal object PreferenceCodec {
    private val appTheme = stringPreferencesKey("app_theme")
    private val accent = longPreferencesKey("accent")
    private val sort = stringPreferencesKey("library_sort")
    private val listView = booleanPreferencesKey("library_list_view")
    private const val GLOBAL_READER = "reader_"

    fun read(values: Preferences): LeafPreferences {
        val defaults = LeafPreferences()
        return LeafPreferences(
            appTheme = enumOrDefault(values[appTheme], defaults.appTheme),
            accent = values[accent]?.takeIf { it in 0L..0xFFFFFFFFL } ?: defaults.accent,
            reader = readReader(values, GLOBAL_READER, defaults.reader),
            sort = enumOrDefault(values[sort], defaults.sort),
            listView = values[listView] ?: defaults.listView,
        )
    }

    fun write(values: MutablePreferences, preferences: LeafPreferences) {
        validate(preferences.reader)
        require(preferences.accent in 0L..0xFFFFFFFFL) { "Use an ARGB accent color." }
        values[appTheme] = preferences.appTheme.name
        values[accent] = preferences.accent
        values[sort] = preferences.sort.name
        values[listView] = preferences.listView
        writeReader(values, GLOBAL_READER, preferences.reader)
    }

    fun readBook(values: Preferences, bookId: String): ReaderPreferences {
        val global = read(values).reader
        val prefix = bookPrefix(bookId)
        return if (values[booleanPreferencesKey(prefix + "override")] == true) {
            readReader(values, prefix, global)
        } else global
    }

    fun writeBook(values: MutablePreferences, bookId: String, preferences: ReaderPreferences) {
        val prefix = bookPrefix(bookId)
        validate(preferences)
        writeReader(values, prefix, preferences)
        values[booleanPreferencesKey(prefix + "override")] = true
    }

    fun resetBook(values: MutablePreferences, bookId: String) {
        val prefix = bookPrefix(bookId)
        values.remove(booleanPreferencesKey(prefix + "override"))
        values.remove(stringPreferencesKey(prefix + "theme"))
        values.remove(doublePreferencesKey(prefix + "font_size"))
        values.remove(stringPreferencesKey(prefix + "font_family"))
        values.remove(doublePreferencesKey(prefix + "line_spacing"))
        values.remove(doublePreferencesKey(prefix + "margins"))
        values.remove(booleanPreferencesKey(prefix + "scroll"))
    }

    private fun readReader(values: Preferences, prefix: String, fallback: ReaderPreferences): ReaderPreferences = ReaderPreferences(
        theme = enumOrDefault(values[stringPreferencesKey(prefix + "theme")], fallback.theme),
        fontSize = values[doublePreferencesKey(prefix + "font_size")]
            ?.takeIf { it.isFinite() && it in 50.0..300.0 } ?: fallback.fontSize,
        fontFamily = values[stringPreferencesKey(prefix + "font_family")]
            ?.takeIf { it.isNotBlank() && it.length <= 100 } ?: fallback.fontFamily,
        lineSpacing = values[doublePreferencesKey(prefix + "line_spacing")]
            ?.takeIf { it.isFinite() && it in 0.8..3.0 } ?: fallback.lineSpacing,
        margins = values[doublePreferencesKey(prefix + "margins")]
            ?.takeIf { it.isFinite() && it in 0.0..3.0 } ?: fallback.margins,
        scroll = values[booleanPreferencesKey(prefix + "scroll")] ?: fallback.scroll,
    )

    private fun writeReader(values: MutablePreferences, prefix: String, preferences: ReaderPreferences) {
        values[stringPreferencesKey(prefix + "theme")] = preferences.theme.name
        values[doublePreferencesKey(prefix + "font_size")] = preferences.fontSize
        values[stringPreferencesKey(prefix + "font_family")] = preferences.fontFamily
        values[doublePreferencesKey(prefix + "line_spacing")] = preferences.lineSpacing
        values[doublePreferencesKey(prefix + "margins")] = preferences.margins
        values[booleanPreferencesKey(prefix + "scroll")] = preferences.scroll
    }

    private fun validate(preferences: ReaderPreferences) {
        require(preferences.fontSize.isFinite() && preferences.fontSize in 50.0..300.0) { "Choose a font size from 50 to 300 percent." }
        require(preferences.fontFamily.isNotBlank() && preferences.fontFamily.length <= 100) { "Choose a readable font family." }
        require(preferences.lineSpacing.isFinite() && preferences.lineSpacing in 0.8..3.0) { "Choose line spacing from 0.8 to 3." }
        require(preferences.margins.isFinite() && preferences.margins in 0.0..3.0) { "Choose margins from 0 to 3." }
    }

    private fun bookPrefix(bookId: String): String {
        require(bookId.isNotBlank() && bookId.length <= 100) { "A book ID is required." }
        return "book_${bookId}_"
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
