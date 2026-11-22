package com.audipp

import android.app.Application
import com.audipp.data.db.LibraryDatabase
import com.audipp.data.local.ArtworkCache
import com.audipp.data.local.ArtworkLoader
import com.audipp.data.local.ArtworkProvider
import com.audipp.data.local.ContentResolverArtworkOpener

class AudippApplication : Application() {
    internal val libraryDatabase by lazy { LibraryDatabase.open(this) }
    val artworkProvider by lazy {
        ArtworkProvider(libraryDatabase.artworkDao(), ArtworkLoader(ContentResolverArtworkOpener(contentResolver), ArtworkCache()))
    }
}
