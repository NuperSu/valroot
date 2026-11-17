package io.valroot.ui

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.storage.BundledSqliteDatabase
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaleDatabaseLoadTest {
    @Test
    fun aLoadFromAReplacedApplicationEndsQuietly() {
        val db = BundledSqliteDatabase(":memory:")
        val stale = PortfolioApplication(db) { 0L }
        // A restore closes the connection the old application holds.
        db.close()
        var failure: Throwable? = null
        runBlocking {
            val load =
                CoroutineScope(Dispatchers.Default + CoroutineExceptionHandler { _, e -> failure = e }).launch {
                    withContext(Dispatchers.IO) { stale.prices.automaticStatus() }
                }
            load.join()
            assertTrue(load.isCancelled)
        }
        assertNull(failure)
    }
}
