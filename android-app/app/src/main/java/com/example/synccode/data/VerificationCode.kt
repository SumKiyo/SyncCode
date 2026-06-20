package com.example.synccode.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "verification_codes")
data class VerificationCode(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "app")
    val app: String,

    @ColumnInfo(name = "code")
    val code: String,

    @ColumnInfo(name = "raw_text")
    val rawText: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long
)