package com.example.photocategorycamera.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.photocategorycamera.domain.CategoryGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryGroupDatabaseTest {
    private lateinit var database: AppDatabase
    private lateinit var groups: CategoryGroupRepository
    private lateinit var categories: CategoryRepository

    @Before fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        groups = CategoryGroupRepository(database.categoryGroupDao())
        categories = CategoryRepository(database.categoryDao(), database.categoryGroupDao())
    }

    @After fun closeDatabase() = database.close()

    @Test fun homeIsPersistentImmutableAndNotRecreatedAsACustomGroup() = runBlocking {
        groups.ensureHome()
        groups.ensureHome()
        assertEquals(listOf("home"), groups.getAll().map { it.id })
        assertEquals("常用", groups.findById("home")?.name)
        assertTrue(groups.rename("home", "旅行").isFailure)
        assertTrue(groups.delete("home").isFailure)
        groups.move("home", 1)
        assertTrue(groups.create(" 常用 ").isFailure)
        assertTrue(groups.create("..").isFailure)
        assertEquals("常用", groups.groups.first().first().name)
    }

    @Test fun groupNamesAreGlobalAndReorderingExcludesHome() = runBlocking {
        val first = groups.create(" Trips ").getOrThrow()
        val second = groups.create("工作").getOrThrow()
        assertTrue(groups.create("trips").isFailure)
        assertTrue(groups.rename(second.id, "TRIPS").isFailure)
        groups.move(second.id, -1)
        assertEquals(listOf("常用", "工作", "Trips"), groups.getAll().map { it.name })
        assertTrue(groups.rename(first.id, "旅行").isSuccess)
    }

    @Test fun directGroupMoveRollsBackEarlierWritesWhenALaterUpdateFails() = runBlocking {
        val first = groups.create("first").getOrThrow()
        val second = groups.create("second").getOrThrow()
        groups.create("third").getOrThrow()
        val dao = database.categoryGroupDao()
        val before = dao.getAll()
        // The abort is reachable only after the earlier UPDATE has successfully changed second.
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_later_group_order_update
            BEFORE UPDATE OF sortOrder ON category_groups
            WHEN OLD.id = '${first.id}'
                AND EXISTS (SELECT 1 FROM category_groups WHERE id = '${second.id}' AND sortOrder = 0)
            BEGIN
                SELECT RAISE(ABORT, 'injected later group order failure');
            END
            """.trimIndent()
        )

        val failure = runCatching { groups.move(second.id, -1) }.exceptionOrNull()

        assertNotNull("The later update must reach the injected database failure", failure)
        assertTrue(failure.toString().contains("injected later group order failure"))
        assertEquals(before, dao.getAll())
        assertEquals(CategoryGroup.HOME_ID, dao.getAll().first().id)
        assertEquals(-1, dao.findById(CategoryGroup.HOME_ID)?.sortOrder)
    }

    @Test fun duplicateNamesAndOrderingAreScopedIncludingInactiveCategories() = runBlocking {
        groups.ensureHome()
        val group = groups.create("旅行").getOrThrow()
        val home = categories.create("票据").getOrThrow()
        val first = categories.create("票据", group.id).getOrThrow()
        val second = categories.create("照片", group.id).getOrThrow()
        assertEquals(0, home.sortOrder)
        assertEquals(0, first.sortOrder)
        categories.setActive(first.id, false)
        assertTrue(categories.create("票据", group.id).isFailure)
        assertTrue(categories.rename(second.id, "票据").isFailure)
        categories.setActive(first.id, true)
        categories.move(second.id, -1)
        assertEquals(listOf("照片", "票据"), categories.getAll(group.id).map { it.name })
        assertEquals(0, categories.findById(home.id)?.sortOrder)
        assertEquals("旅行/票据", categories.findById(first.id)?.directoryLabel)
        groups.rename(group.id, "出差").getOrThrow()
        assertEquals("出差/票据", categories.findById(first.id)?.directoryLabel)
        assertEquals("出差", categories.categories.first().first { it.id == first.id }.groupName)
    }

    @Test fun importIsScopedAndDeletionNeverSeedsAnEmptyHomeAgain() = runBlocking {
        groups.ensureHome()
        val group = groups.create("旅行").getOrThrow()
        categories.create("票据").getOrThrow()
        assertEquals(listOf("票据"), categories.importDirectories(listOf("票据"), group.id).createdNames)
        val imported = categories.getAll(group.id).single()
        categories.setActive(imported.id, false)
        assertEquals(1, categories.importDirectories(listOf("票据"), group.id).existingCount)
        assertFalse(categories.findById(imported.id)!!.isActive)
        assertTrue(groups.delete(group.id).isFailure)
        categories.delete(imported.id)
        assertTrue(groups.delete(group.id).isSuccess)
        categories.delete(categories.getAll(CategoryGroup.HOME_ID).single().id)
        categories.ensureSeeded()
        assertEquals(emptyList<CategoryEntity>(), database.categoryDao().getAll())
        assertTrue(categories.create("丢失", "missing").isFailure)
    }

    @Test fun freshSeedCreatesHomeAndOldCategoriesOnlyOnce() = runBlocking {
        categories.ensureSeeded()
        assertEquals("常用", groups.findById("home")?.name)
        assertEquals(listOf("工作记录", "生活随拍", "票据"), categories.getAll().map { it.name })
        categories.ensureSeeded()
        assertEquals(3, categories.getAll().size)
    }

    @Test fun databaseUniqueIndexesIncludeInactiveCategoriesAndGlobalGroupNames() = runBlocking {
        groups.ensureHome()
        val group = groups.create("Trips").getOrThrow()
        val dao = database.categoryDao()
        dao.insert(CategoryEntity("home-one", "Receipt", 0, false, 1))
        dao.insert(CategoryEntity("trip-one", "receipt", 0, true, 2, group.id))
        assertTrue(runCatching { dao.insert(CategoryEntity("home-two", "RECEIPT", 1, true, 3)) }.isFailure)
        assertEquals(2, dao.getAll().size)
        assertTrue(runCatching {
            database.categoryGroupDao().insert(CategoryGroupEntity("duplicate", "trips", 1, 3))
        }.isFailure)
        assertEquals(2, groups.getAll().size)
    }
}
