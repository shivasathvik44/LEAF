package com.leaf.reader.data.readium

import com.leaf.reader.data.local.database.LeafDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only access to the application's database for targeted UI failure injection. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReaderUiDependencies {
    fun database(): LeafDatabase
}
