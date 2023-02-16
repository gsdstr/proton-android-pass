package proton.android.pass.data.impl.fakes

import proton.android.pass.data.impl.db.PassDatabase
import proton.android.pass.data.impl.db.dao.ItemsDao
import proton.android.pass.data.impl.db.dao.PassEventsDao
import proton.android.pass.data.impl.db.dao.SelectedShareDao
import proton.android.pass.data.impl.db.dao.ShareKeysDao
import proton.android.pass.data.impl.db.dao.ShareSelectedShareDao
import proton.android.pass.data.impl.db.dao.SharesDao

class TestPassDatabase : PassDatabase {
    override fun sharesDao(): SharesDao {
        throw IllegalStateException("This method should not be called")
    }

    override fun itemsDao(): ItemsDao {
        throw IllegalStateException("This method should not be called")
    }

    override fun shareKeysDao(): ShareKeysDao {
        throw IllegalStateException("This method should not be called")
    }

    override fun passEventsDao(): PassEventsDao {
        throw IllegalStateException("This method should not be called")
    }

    override fun selectedShareDao(): SelectedShareDao {
        throw IllegalStateException("This method should not be called")
    }

    override fun shareSelectedShareDao(): ShareSelectedShareDao {
        throw IllegalStateException("This method should not be called")
    }

    override suspend fun <R> inTransaction(block: suspend () -> R): R = block()
}
