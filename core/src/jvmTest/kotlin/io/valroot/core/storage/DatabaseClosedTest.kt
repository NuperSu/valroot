package io.valroot.core.storage

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatabaseClosedTest {
    @Test
    fun aClosedConnectionRefusesEveryUseAsACancellation() {
        val db = JvmSqliteDatabase(":memory:")
        db.execute("CREATE TABLE t (v TEXT)")
        db.close()
        val refused = assertFailsWith<DatabaseClosed> { db.query("SELECT v FROM t") }
        assertTrue((refused as Throwable) is CancellationException)
        assertFailsWith<DatabaseClosed> { db.execute("INSERT INTO t VALUES ('x')") }
        assertFailsWith<DatabaseClosed> { db.transaction { } }
    }
}
