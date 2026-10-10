package com.example.photocategorycamera.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.photocategorycamera.AppContainer
import com.example.photocategorycamera.data.DocumentDraftEntity
import com.example.photocategorycamera.domain.DocumentEffect
import com.example.photocategorycamera.domain.DocumentQuad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DocumentReviewState(
    val draft: DocumentDraftEntity? = null,
    val sourcePreview: Bitmap? = null,
    val processedPreview: Bitmap? = null,
    val processing: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
)

class DocumentViewModel(private val container: AppContainer, private val id: String) : ViewModel() {
    private val mutableState = MutableStateFlow(DocumentReviewState())
    val state = mutableState.asStateFlow()
    private var editJob: Job? = null
    private var pendingEdit: DocumentDraftEntity? = null

    init {
        viewModelScope.launch {
            container.documents.editable.collectLatest { current ->
                val draft = current?.takeIf { it.id == id }
                if (draft == null) {
                    pendingEdit = null
                    mutableState.value = mutableState.value.copy(draft = null, processing = false)
                    return@collectLatest
                }
                // Room may emit an earlier edit while newer user input is waiting to be persisted.
                pendingEdit?.takeIf { it.sourcePath == draft.sourcePath }?.let { requested ->
                    if (requested.quadValue != draft.quadValue || requested.effectValue != draft.effectValue ||
                        requested.quarterTurns != draft.quarterTurns) return@collectLatest
                }
                pendingEdit = null
                val sameSource = mutableState.value.draft?.sourcePath == draft.sourcePath
                val cachedSource = mutableState.value.sourcePreview.takeIf { sameSource }
                mutableState.value = mutableState.value.copy(draft = draft, processing = true, error = null,
                    processedPreview = null, sourcePreview = cachedSource)
                if (draft.stage != "EDITING" || draft.replacementPath != null) return@collectLatest
                try {
                    val source = cachedSource ?: withContext(Dispatchers.IO) {
                        container.documentProcessor.loadUpright(File(draft.sourcePath), 1280)
                    }
                    mutableState.value = mutableState.value.copy(sourcePreview = source)
                    val result = withContext(Dispatchers.Default) {
                        container.documentProcessor.render(File(draft.sourcePath), draft.quad, draft.effect, draft.quarterTurns, 1280)
                    }
                    if (pendingEdit != null || mutableState.value.draft != draft) {
                        result.recycle()
                        return@collectLatest
                    }
                    mutableState.value = mutableState.value.copy(processedPreview = result, processing = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (pendingEdit == null && mutableState.value.draft == draft) {
                        mutableState.value = mutableState.value.copy(processing = false, error = error.message ?: "文档处理失败，原图已保留")
                    }
                } catch (error: LinkageError) {
                    if (pendingEdit == null && mutableState.value.draft == draft) {
                        mutableState.value = mutableState.value.copy(processing = false, error = "本机无法加载文档处理组件，草稿已保留")
                    }
                }
            }
        }
    }

    fun edit(
        quad: DocumentQuad? = null,
        effect: DocumentEffect? = null,
        quarterTurns: Int? = null,
    ) {
        val draft = mutableState.value.draft ?: return
        if (mutableState.value.saving || draft.stage != "EDITING" || draft.replacementPath != null) return
        val requested = draft.copy(quadValue = (quad ?: draft.quad).encode(),
            effectValue = (effect ?: draft.effect).name,
            quarterTurns = ((quarterTurns ?: draft.quarterTurns) % 4 + 4) % 4)
        if (requested == draft) return
        pendingEdit = requested
        mutableState.value = mutableState.value.copy(draft = requested, processing = true, processedPreview = null, error = null)
        val previousEdit = editJob
        editJob = viewModelScope.launch {
            previousEdit?.join()
            try {
                container.documents.edit(id, requested.quad, requested.effect, requested.quarterTurns)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (pendingEdit == requested) {
                    pendingEdit = null
                    mutableState.value = mutableState.value.copy(processing = false, error = error.message)
                }
            }
        }
    }

    fun leave(onLeave: () -> Unit) {
        if (mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(saving = true)
        viewModelScope.launch { editJob?.join(); onLeave() }
    }

    fun save(onQueued: () -> Unit) {
        if (mutableState.value.saving || mutableState.value.processing) return
        mutableState.value = mutableState.value.copy(saving = true, error = null)
        viewModelScope.launch {
            editJob?.join()
            runCatching { container.documents.submit(id).getOrThrow() }
                .onSuccess { onQueued() }
                .onFailure { mutableState.value = mutableState.value.copy(saving = false, error = it.message ?: "保存失败，草稿已保留") }
        }
    }

    fun discard(onDiscarded: () -> Unit) {
        if (mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(saving = true)
        viewModelScope.launch {
            editJob?.join()
            runCatching { container.documents.discard(id) }
                .onSuccess { onDiscarded() }
                .onFailure { mutableState.value = mutableState.value.copy(saving = false, error = it.message) }
        }
    }

    companion object {
        fun factory(container: AppContainer, id: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DocumentViewModel(container, id) as T
        }
    }
}
