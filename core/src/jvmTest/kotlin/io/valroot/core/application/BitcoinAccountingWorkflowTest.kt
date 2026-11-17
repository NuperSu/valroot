package io.valroot.core.application

import io.valroot.core.storage.BundledSqliteDatabase
import io.valroot.testing.verifyBitcoinAccountingWorkflow
import kotlin.test.Test

class BitcoinAccountingWorkflowTest {
    @Test
    fun sourceHistoryVerificationAccountingAndRestorePreserveChainOrder() {
        verifyBitcoinAccountingWorkflow { BundledSqliteDatabase(":memory:") }
    }
}
