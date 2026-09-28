/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.repository

import android.accounts.Account
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import at.bitfire.dav4jvm.ktor.exception.HttpException
import at.bitfire.davdroid.MockEngineQueue
import at.bitfire.davdroid.TestUtils
import at.bitfire.davdroid.accounts.LegacyAccount
import at.bitfire.davdroid.db.AppDatabase
import at.bitfire.davdroid.db.HomeSet
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.network.HttpClientBuilder
import at.bitfire.davdroid.servicedetection.RefreshCollectionsWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

@HiltAndroidTest
class DavCollectionRepositoryTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var collectionRepository: DavCollectionRepository

    @Inject
    @ApplicationContext
    lateinit var context: Context

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @BindValue
    @JvmField
    val httpClientBuilder: HttpClientBuilder = mockk()

    private val accountId = LegacyAccount(Account("test", "test"))
    private lateinit var homeSet: HomeSet

    private val mockEngineQueue = MockEngineQueue()
    private val engine = mockEngineQueue.engine

    @Before
    fun setUp() {
        hiltRule.inject()
        TestUtils.setUpWorkManager(context, workerFactory)

        // keep the post-creation refresh from sending its own requests to the mock engine
        mockkObject(RefreshCollectionsWorker)
        every { RefreshCollectionsWorker.enqueue(any(), any()) } returns Pair("", mockk())

        coEvery { httpClientBuilder.fromAccountAsync(any(), any()) } returns httpClientBuilder
        every { httpClientBuilder.build() } answers { HttpClient(engine) }

        val serviceId = db.serviceDao().insertOrReplaceBlocking(
            Service(id = 0, accountName = accountId.androidAccount.name, type = Service.TYPE_CALDAV)
        )
        val homeSetUrl = Url("https://example.com/calendars/")
        db.homeSetDao().insertBlocking(HomeSet(id = 0, serviceId = serviceId, personal = true, url = homeSetUrl))
        homeSet = db.homeSetDao().getByUrlBlocking(serviceId, homeSetUrl.toString())!!
    }

    @After
    fun tearDown() {
        db.serviceDao().deleteAllBlocking()
        engine.close()
        unmockkObject(RefreshCollectionsWorker)
    }


    @Test
    fun testCreateCalendar_MkCalendarSupported() = runTest {
        mockEngineQueue.enqueue(HttpStatusCode.Created)

        createCalendar()

        assertEquals(listOf("MKCALENDAR"), engine.requestHistory.map { it.method.value })
        assertCollectionCreated()
    }

    @Test
    fun testCreateCalendar_MkCalendarNotImplemented_FallsBackToMkCol() = runTest {
        mockEngineQueue
            .enqueue(HttpStatusCode.NotImplemented)
            .enqueue(HttpStatusCode.Created)

        createCalendar()

        val requests = engine.requestHistory
        assertEquals(listOf("MKCALENDAR", "MKCOL"), requests.map { it.method.value })
        assertEquals(requests[0].url, requests[1].url)
        val mkColBody = requests[1].body.toByteArray().decodeToString()
        assertTrue(mkColBody.contains(Regex("<mkcol[ >]")))
        assertTrue(mkColBody.contains("<CAL:calendar"))
        assertCollectionCreated()
    }

    @Test
    fun testCreateCalendar_MkCalendarNotAllowed_NoFallback() = runTest {
        // 405 means creation isn't allowed at this URL, not that MKCALENDAR is unsupported
        mockEngineQueue.enqueue(HttpStatusCode.MethodNotAllowed)

        val e = assertCreateCalendarFails()

        assertEquals(405, e.statusCode)
        assertEquals(listOf("MKCALENDAR"), engine.requestHistory.map { it.method.value })
        assertNoCollectionCreated()
    }

    @Test
    fun testCreateCalendar_MkCalendarForbidden_NoFallback() = runTest {
        mockEngineQueue.enqueue(HttpStatusCode.Forbidden)

        val e = assertCreateCalendarFails()

        assertEquals(403, e.statusCode)
        assertEquals(listOf("MKCALENDAR"), engine.requestHistory.map { it.method.value })
        assertNoCollectionCreated()
    }

    @Test
    fun testCreateCalendar_BothMethodsFail() = runTest {
        mockEngineQueue
            .enqueue(HttpStatusCode.NotImplemented)
            .enqueue(HttpStatusCode.Forbidden)

        val e = assertCreateCalendarFails()

        assertEquals(403, e.statusCode)
        assertEquals(501, (e.suppressed.single() as HttpException).statusCode)
        assertNoCollectionCreated()
    }


    // helpers

    private suspend fun createCalendar() {
        collectionRepository.createCalendar(
            accountId = accountId,
            homeSet = homeSet,
            color = null,
            displayName = "Test Calendar",
            description = null,
            timeZoneId = null,
            supportVEVENT = true,
            supportVTODO = true,
            supportVJOURNAL = true
        )
    }

    private suspend fun assertCreateCalendarFails(): HttpException {
        val e = runCatching { createCalendar() }.exceptionOrNull()
        assertTrue("Expected HttpException, got $e", e is HttpException)
        return e as HttpException
    }

    private suspend fun assertCollectionCreated() {
        assertNotNull(collectionRepository.getByService(homeSet.serviceId).singleOrNull())
        verify { RefreshCollectionsWorker.enqueue(any(), homeSet.serviceId) }
    }

    private suspend fun assertNoCollectionCreated() {
        assertNull(collectionRepository.getByService(homeSet.serviceId).singleOrNull())
    }

}
