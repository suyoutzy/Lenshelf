package com.example.photocategorycamera.data

import com.example.photocategorycamera.domain.CategoryGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CategoryGroupRepositoryTest {
    private val categoryRows = MutableStateFlow<List<CategoryEntity>>(emptyList())
    private val groupRows = MutableStateFlow<List<CategoryGroupEntity>>(emptyList())
    private val categoryDao = MemoryCategoryDao(categoryRows, groupRows)
    private val groupDao = MemoryGroupDao(groupRows, categoryRows)
    private val categories = CategoryRepository(categoryDao, groupDao)
    private val groups = CategoryGroupRepository(groupDao)

    @Test fun `status change appends to corresponding section while no-op preserves order`() = runBlocking {
        groups.ensureHome()
        val first = categories.create("first").getOrThrow()
        val second = categories.create("second").getOrThrow()
        val third = categories.create("third").getOrThrow()
        categories.setActive(second.id, false)
        categories.setActive(first.id, false)
        assertEquals(listOf(third.id, second.id, first.id), categories.getAll().map { it.id })
        val before = categories.getAll()
        categories.setActive(second.id, false)
        assertEquals(before, categories.getAll())
        categories.setActive(second.id, true)
        assertEquals(listOf(third.id, second.id, first.id), categories.getAll().map { it.id })
    }

    @Test fun `direction fallback reorders inactive section without touching active section`() = runBlocking {
        groups.ensureHome()
        val active = categories.create("active").getOrThrow()
        val first = categories.create("first").getOrThrow()
        val second = categories.create("second").getOrThrow()
        categories.setActive(first.id, false)
        categories.setActive(second.id, false)
        categories.move(second.id, -1)
        assertEquals(listOf(active.id, second.id, first.id), categories.getAll().map { it.id })
        assertEquals(active.sortOrder, categories.findById(active.id)?.sortOrder)
    }

    @Test fun `scoped duplicates imports and ordering preserve other groups`() = runBlocking {
        groups.ensureHome()
        val group = groups.create("旅行").getOrThrow()
        val home = categories.create("票据").getOrThrow()
        val first = categories.create("票据", group.id).getOrThrow()
        val second = categories.create("照片", group.id).getOrThrow()
        assertEquals(0, first.sortOrder)
        categories.setActive(first.id, false)
        assertTrue(categories.create("票据", group.id).isFailure)
        assertTrue(categories.rename(second.id, "票据").isFailure)
        assertEquals(1, categories.importDirectories(listOf("票据"), group.id).existingCount)
        categories.setActive(first.id, true)
        categories.move(second.id, -1)
        assertEquals(listOf("照片", "票据"), categories.getAll(group.id).map { it.name })
        assertEquals(0, categories.findById(home.id)?.sortOrder)
        groups.rename(group.id, "出差").getOrThrow()
        assertEquals("出差/票据", categories.findById(first.id)?.directoryLabel)
        assertEquals("出差/票据", categories.categories.first().first { it.id == first.id }.directoryLabel)
        assertTrue(categories.create("丢失", "missing").isFailure)
    }

    @Test fun `Home cannot be mutated and group duplicates are global`() = runBlocking {
        val first = groups.create("Trips").getOrThrow()
        val second = groups.create("工作").getOrThrow()
        assertTrue(groups.create("trips").isFailure)
        assertTrue(groups.create(" 常用 ").isFailure)
        assertTrue(groups.create("..").isFailure)
        assertTrue(groups.rename("home", "更改").isFailure)
        assertTrue(groups.delete("home").isFailure)
        assertTrue(groups.rename(second.id, "TRIPS").isFailure)
        groups.move(second.id, -1)
        groups.move("home", 1)
        assertEquals(listOf("常用", "工作", "Trips"), groups.groups.first().map { it.name })
        categories.create("票据", first.id).getOrThrow()
        assertTrue(groups.delete(first.id).isFailure)
        categories.delete(categories.getAll(first.id).single().id)
        assertTrue(groups.delete(first.id).isSuccess)
    }

    @Test fun `direct group reorder rejects stale and invalid snapshots before writing`() = runBlocking {
        val first = groups.create("first").getOrThrow()
        val second = groups.create("second").getOrThrow()
        groupDao.setSortOrder(first.id, 5)
        groupDao.setSortOrder(second.id, 9)
        val before = groupRows.value
        val expected = listOf(first.id, second.id)

        assertTrue(runCatching { groups.reorder(expected.reversed(), expected) }.isFailure)
        assertEquals(before, groupRows.value)
        assertTrue(runCatching { groups.reorder(expected, listOf(second.id, second.id)) }.isFailure)
        assertEquals(before, groupRows.value)
        assertTrue(runCatching { groups.reorder(expected, listOf(CategoryGroup.HOME_ID, second.id)) }.isFailure)
        assertEquals(before, groupRows.value)
        groups.reorder(expected, expected)
        assertEquals(before, groupRows.value)
    }

    @Test fun `persistent Home marks initialization even after all categories are removed`() = runBlocking {
        categories.ensureSeeded()
        assertEquals(listOf("工作记录", "生活随拍", "票据"), categories.getAll().map { it.name })
        categories.getAll().forEach { categories.delete(it.id) }
        categories.ensureSeeded()
        assertTrue(categories.getAll().isEmpty())
        assertEquals("常用", groups.findById(CategoryGroup.HOME_ID)?.name)
    }
}

private class MemoryCategoryDao(
    private val rows: MutableStateFlow<List<CategoryEntity>>,
    private val groups: MutableStateFlow<List<CategoryGroupEntity>>,
) : CategoryDao {
    override fun observeAll() = rows.map { sorted(it) }
    override suspend fun getAll() = sorted(rows.value)
    override suspend fun getForGroup(groupId: String) = sorted(rows.value.filter { it.groupId == groupId })
    override suspend fun findById(id: String) = rows.value.firstOrNull { it.id == id }
    override suspend fun count() = rows.value.size
    override suspend fun countByName(name: String, groupId: String) = rows.value.count { it.groupId == groupId && it.name.equals(name, true) }
    override suspend fun countByNameExcludingId(name: String, id: String, groupId: String) = rows.value.count { it.id != id && it.groupId == groupId && it.name.equals(name, true) }
    override suspend fun maxSortOrder(groupId: String) = rows.value.filter { it.groupId == groupId }.maxOfOrNull { it.sortOrder } ?: -1
    override suspend fun insert(entity: CategoryEntity) { check(countByName(entity.name, entity.groupId) == 0); rows.value += entity }
    override suspend fun insertAll(entities: List<CategoryEntity>) { entities.forEach { if (countByName(it.name, it.groupId) == 0) insert(it) } }
    override suspend fun insertHomeForSeed(): Long {
        if (groups.value.any { it.id == "home" }) return -1
        groups.value += CategoryGroupEntity("home", "首页", -1, 0)
        return 1
    }
    override suspend fun update(entity: CategoryEntity) { rows.value = rows.value.map { if (it.id == entity.id) entity else it } }
    override suspend fun setActive(id: String, active: Boolean) { findById(id)?.let { update(it.copy(isActive = active)) } }
    override suspend fun setName(id: String, name: String) { findById(id)?.let { update(it.copy(name = name)) } }
    override suspend fun setSortOrder(id: String, orderValue: Int) { findById(id)?.let { update(it.copy(sortOrder = orderValue)) } }
    override suspend fun deleteById(id: String) { rows.value = rows.value.filterNot { it.id == id } }
    private fun sorted(values: List<CategoryEntity>) = values.sortedWith(compareByDescending<CategoryEntity> { it.isActive }.thenBy { it.sortOrder }.thenBy { it.createdAt })
}

private class MemoryGroupDao(
    private val rows: MutableStateFlow<List<CategoryGroupEntity>>,
    private val categories: MutableStateFlow<List<CategoryEntity>>,
) : CategoryGroupDao {
    override fun observeAll() = rows.map { sorted(it) }
    override suspend fun getAll() = sorted(rows.value)
    override suspend fun findById(id: String) = rows.value.firstOrNull { it.id == id }
    override suspend fun countByName(name: String, excludedId: String) = rows.value.count { it.id != excludedId && it.name.equals(name, true) }
    override suspend fun maxSortOrder() = rows.value.filter { it.id != "home" }.maxOfOrNull { it.sortOrder } ?: -1
    override suspend fun countCategories(groupId: String) = categories.value.count { it.groupId == groupId }
    override suspend fun insert(entity: CategoryGroupEntity) { check(countByName(entity.name, "") == 0); rows.value += entity }
    override suspend fun insertHome(entity: CategoryGroupEntity) { if (findById(entity.id) == null) insert(entity) }
    override suspend fun setName(id: String, name: String) { rows.value = rows.value.map { if (it.id == id && id != "home") it.copy(name = name) else it } }
    override suspend fun deleteEmpty(id: String): Int {
        if (id == "home" || countCategories(id) > 0 || findById(id) == null) return 0
        rows.value = rows.value.filterNot { it.id == id }
        return 1
    }
    override suspend fun setSortOrder(id: String, sortOrder: Int) { rows.value = rows.value.map { if (it.id == id && id != "home") it.copy(sortOrder = sortOrder) else it } }
    private fun sorted(values: List<CategoryGroupEntity>) = values.sortedWith(compareBy<CategoryGroupEntity> { it.id != "home" }.thenBy { it.sortOrder }.thenBy { it.createdAt })
}
