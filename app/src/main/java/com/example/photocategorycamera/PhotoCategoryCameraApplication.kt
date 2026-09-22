package com.example.photocategorycamera

import android.app.Application

class PhotoCategoryCameraApplication : Application() {
    val container by lazy { AppContainer(this) }
}
