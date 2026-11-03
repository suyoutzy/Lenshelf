package com.example.photocategorycamera.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.photocategorycamera.data.CaptureTaskEntity
import com.example.photocategorycamera.document.DocumentProcessor
import com.example.photocategorycamera.domain.MediaKind
import com.example.photocategorycamera.domain.PostCaptureNote
import com.example.photocategorycamera.photo.MediaStampRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PendingAnnotationSheet(
    state: PendingAnnotationUiState,
    onDraftChange: (String) -> Unit,
    onCollapse: () -> Unit,
    onSave: () -> Unit,
) {
    val task = state.task ?: return
    val collapse by rememberUpdatedState(onCollapse)
    // A hide gesture requests durable collapse; the sheet stays open if the write fails.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = {
        if (it == SheetValue.Hidden) { collapse(); false } else true
    })
    ModalBottomSheet(onDismissRequest = onCollapse, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("拍后备注", style = MaterialTheme.typography.titleMedium)
                Text("${state.position} / ${state.total}", style = MaterialTheme.typography.labelMedium)
            }
            Text("保存到：${task.category().directoryLabel}", style = MaterialTheme.typography.bodySmall)
            NoteMediaPreview(task, state.draft)
            OutlinedTextField(
                value = state.draft, onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth().testTag("note-input"),
                label = { Text("备注") }, placeholder = { Text("填写备注（可留空）") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                enabled = !state.busy, minLines = 1, maxLines = 3,
                isError = state.error != null,
                supportingText = { Text(state.error ?: "${PostCaptureNote.count(state.draft)} / 60") },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCollapse, enabled = !state.busy) { Text("收起") }
                Spacer(Modifier.width(10.dp))
                Button(onClick = onSave, enabled = !state.busy) { Text(if (state.busy) "正在保存…" else "保存") }
            }
        }
    }
}

private data class NotePreview(val bitmap: Bitmap, val durationMs: Long? = null)

@Composable
private fun NoteMediaPreview(task: CaptureTaskEntity, draft: String) {
    // Decode once per item, not once per keystroke. Published preview copies are reclaimed by GC.
    val path = task.sourcePath ?: task.tempPath
    var preview by remember(task.id, path) { mutableStateOf<NotePreview?>(null) }
    LaunchedEffect(task.id, path) {
        preview = withContext(Dispatchers.IO) {
            runCatching {
                val source = File(path)
                if (task.kind == MediaKind.PHOTO) NotePreview(DocumentProcessor().loadUpright(source, 720))
                else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(source.path)
                        val bitmap = checkNotNull(retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 720, 720))
                        NotePreview(bitmap, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
                    } finally { retriever.release() }
                }
            }.getOrNull()
        }
    }
    val loaded = preview
    if (loaded == null) {
        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { Text("素材预览") }
        return
    }
    val bitmap = loaded.bitmap
    val text = task.stampRecipe.copy(note = PostCaptureNote.validate(draft).getOrDefault("")).text
    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        Image(bitmap.asImageBitmap(), "${if (task.kind == MediaKind.VIDEO) "视频首帧" else "照片"}备注预览",
            Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(Modifier.fillMaxSize()) {
            val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
            val width = bitmap.width * scale; val height = bitmap.height * scale
            val canvas: AndroidCanvas = drawContext.canvas.nativeCanvas
            canvas.save()
            canvas.translate((size.width-width)/2, (size.height-height)/2)
            MediaStampRenderer.draw(canvas, width, height, text)
            canvas.restore()
        }
    }
    if (task.kind == MediaKind.VIDEO) {
        val seconds = (loaded.durationMs ?: 0) / 1000
        Text("视频 · ${seconds / 60}:${(seconds % 60).toString().padStart(2,'0')}", style = MaterialTheme.typography.labelSmall)
    }
}
