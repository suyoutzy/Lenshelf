package com.example.photocategorycamera.photo

import kotlinx.coroutines.sync.Mutex

/** CameraX may capture the next source, but large bitmap/native processing runs one shot at a time. */
internal object ImageProcessingGate {
    val mutex = Mutex()
}
