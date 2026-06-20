package com.example.synccode.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VerificationCodeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(code: VerificationCode): Long

    @Query("SELECT * FROM verification_codes ORDER BY timestamp DESC")
    fun getAllCodes(): Flow<List<VerificationCode>>

    @Query("SELECT COUNT(*) FROM verification_codes")
    fun getCount(): Int

    @Query("DELETE FROM verification_codes")
    fun clearAll(): Int
}