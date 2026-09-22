package com.example.photocategorycamera.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryDatabaseTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: CategoryRepository

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CategoryRepository(database.categoryDao())
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun seedsCreatesMovesAndArchivesCategories() = runBlocking {
        repository.ensureSeeded()
        val seeded = repository.categories.first()
        assertEquals(listOf("工作记录", "生活随拍", "票据"), seeded.map { it.name })

        assertTrue(repository.create("实验记录").isSuccess)
        assertTrue(repository.create("实验记录").isFailure)

        val work = repository.getAll().first { it.name == "工作记录" }
        repository.move(work.id, 1)
        val moved = repository.getAll().filter { it.isActive }.sortedBy { it.sortOrder }
        assertEquals("生活随拍", moved.first().name)

        repository.setActive(work.id, false)
        assertFalse(repository.findById(work.id)!!.isActive)

        repository.delete(work.id)
        assertEquals(null, repository.findById(work.id))
    }

    @Test
    fun importsNewDirectoryAndLeavesArchivedCategoryInactiveIdempotently() = runBlocking {
        repository.ensureSeeded()
        val receipt = repository.getAll().first { it.name == "票据" }
        repository.setActive(receipt.id, false)

        val first = repository.importDirectories(listOf("票据", "旅行", "工作记录"))
        assertEquals(listOf("旅行"), first.createdNames)
        assertEquals(2, first.existingCount)
        assertFalse(repository.findById(receipt.id)!!.isActive)

        val second = repository.importDirectories(listOf("票据", "旅行", "工作记录"))
        assertEquals(0, second.importedCount)
        assertEquals(3, second.existingCount)
        assertEquals(1, repository.getAll().count { it.name == "旅行" })
    }

    @Test
    fun renamesCategoryAndRejectsDuplicateName() = runBlocking {
        repository.ensureSeeded()
        val work = repository.getAll().first { it.name == "工作记录" }

        assertTrue(repository.rename(work.id, "  项目记录  ").isSuccess)
        assertEquals("项目记录", repository.findById(work.id)?.name)
        assertTrue(repository.rename(work.id, "生活随拍").isFailure)
        assertEquals("项目记录", repository.findById(work.id)?.name)
    }
}
