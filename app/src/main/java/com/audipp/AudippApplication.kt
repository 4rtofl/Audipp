package com.audipp

import android.app.Application
import com.audipp.data.db.LibraryDatabase

class AudippApplication : Application() {
    internal val libraryDatabase by lazy { LibraryDatabase.open(this) }
}
