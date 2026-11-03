package com.example.photocategorycamera.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.photocategorycamera.data.CaptureTaskEntity
import com.example.photocategorycamera.data.CaptureTaskRepository
import com.example.photocategorycamera.domain.PostCaptureNote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PendingAnnotationUiState(
    val task: CaptureTaskEntity? = null,
    val draft: String = "",
    val position: Int = 1,
    val total: Int = 0,
    val busy: Boolean = false,
    val opening: Boolean = false,
    val error: String? = null,
) {
    val visible: Boolean get() = task != null || opening
}

/** One editor belongs to the application UI, rather than to a category or camera route. */
internal class PendingAnnotationViewModel(private val repository: CaptureTaskRepository) : ViewModel() {
    private val mutable = MutableStateFlow(PendingAnnotationUiState())
    val state = mutable.asStateFlow()
    private val edits = Mutex()
    private var background = false
    private var collapseRequested = false

    fun open(id: String? = null) {
        if (mutable.value.visible || background) return
        collapseRequested = false
        mutable.value = PendingAnnotationUiState(opening = true)
        viewModelScope.launch {
            val pending = repository.awaitingNotes.first()
            val task = if (id == null) pending.firstOrNull() else pending.firstOrNull { it.id == id }
            mutable.value = if (task == null || background || collapseRequested) PendingAnnotationUiState()
                else PendingAnnotationUiState(task, task.noteDraft, total = pending.size)
        }
    }

    fun changeDraft(text: String) {
        val current = mutable.value
        val task = current.task ?: return
        if (current.busy) return
        val validation = PostCaptureNote.validate(text)
        if (validation.isFailure) {
            mutable.value = current.copy(error = validation.exceptionOrNull()?.message)
            return
        }
        mutable.value = current.copy(draft = text, error = null)
        // Main-immediate launch + the mutex preserve edit order; collapse/save queue behind these writes.
        viewModelScope.launch {
            edits.withLock {
                val result = repository.updateAnnotationDraft(task.id, text)
                if (result.isFailure && mutable.value.task?.id == task.id) {
                    mutable.value = mutable.value.copy(error = result.exceptionOrNull()?.message ?: "备注暂未保存，请重试")
                }
            }
        }
    }

    fun collapse() {
        collapseRequested = true
        val current = mutable.value
        val task = current.task ?: return
        if (current.busy) return
        mutable.value = current.copy(busy = true)
        viewModelScope.launch {
            edits.withLock {
                repository.updateAnnotationDraft(task.id, current.draft).fold(
                    { mutable.value = PendingAnnotationUiState() },
                    { mutable.value = current.copy(error = it.message ?: "备注未保存，暂时无法收起") },
                )
            }
        }
    }

    fun save() {
        val current = mutable.value
        val task = current.task ?: return
        if (current.busy) return
        mutable.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            edits.withLock {
                repository.confirmAnnotation(task.id, current.draft).fold(
                    {
                        val remaining = repository.awaitingNotes.first()
                        val next = remaining.firstOrNull()
                        mutable.value = if (next == null || background || collapseRequested) PendingAnnotationUiState()
                        else PendingAnnotationUiState(next, next.noteDraft, current.position + 1,
                            current.position + remaining.size)
                    },
                    { error ->
                        mutable.value = current.copy(error = error.message ?: "保存失败，素材和备注已保留")
                        if (background || collapseRequested) collapse()
                    },
                )
            }
        }
    }

    fun onBackground() { background = true; collapse() }
    fun onForeground() { background = false }

    companion object {
        fun factory(repository: CaptureTaskRepository) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return PendingAnnotationViewModel(repository) as T
            }
        }
    }
}
