package com.leaf.reader.data.local.database

import androidx.room.TypeConverter
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.domain.model.ReadingStatus

class EnumConverters {
    @TypeConverter fun formatToString(value: PublicationFormat): String = value.name
    @TypeConverter fun stringToFormat(value: String): PublicationFormat = PublicationFormat.valueOf(value)
    @TypeConverter fun statusToString(value: ReadingStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): ReadingStatus = ReadingStatus.valueOf(value)
}
