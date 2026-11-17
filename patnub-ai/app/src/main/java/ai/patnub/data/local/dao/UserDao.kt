package ai.patnub.data.local.dao

import ai.patnub.data.local.entity.UserEntity
import ai.patnub.data.local.entity.UserRole
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    /** Inserts users that don't exist yet and leaves existing rows untouched. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(users: List<UserEntity>)

    @Upsert
    suspend fun upsert(user: UserEntity)

    @Query("SELECT * FROM users WHERE user_id = :userId")
    suspend fun getById(userId: String): UserEntity?

    @Query("SELECT * FROM users WHERE role = :role ORDER BY display_name")
    fun observeByRole(role: UserRole): Flow<List<UserEntity>>
}
