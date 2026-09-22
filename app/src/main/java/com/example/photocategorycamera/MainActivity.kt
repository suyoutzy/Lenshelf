package com.example.photocategorycamera

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.example.photocategorycamera.ui.AppRoot
import com.example.photocategorycamera.ui.AppViewModel
import com.example.photocategorycamera.ui.theme.PhotoCategoryCameraTheme

class MainActivity : ComponentActivity() {
    var volumeShutter: (() -> Unit)? = null
    private val consumedVolumeKeys = mutableSetOf<Int>()

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if ((keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) && volumeShutter != null) {
            consumedVolumeKeys.add(keyCode)
            if (event.repeatCount == 0) volumeShutter?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (consumedVolumeKeys.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private val container get() = (application as PhotoCategoryCameraApplication).container
    private val appViewModel by viewModels<AppViewModel> { AppViewModel.factory(container) }

    private val directoryPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            appViewModel.reportMessage("未选择根目录")
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            appViewModel.configureRoot(uri)
        } catch (error: SecurityException) {
            appViewModel.reportMessage("无法持久保存目录授权：${error.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhotoCategoryCameraTheme {
                AppRoot(
                    appViewModel = appViewModel,
                    container = container,
                    onChooseDirectory = { directoryPicker.launch(null) },
                )
            }
        }
    }
}
