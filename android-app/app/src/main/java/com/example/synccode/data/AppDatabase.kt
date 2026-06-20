package com.example.synccode.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [VerificationCode::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun verificationCodeDao(): VerificationCodeDao
}