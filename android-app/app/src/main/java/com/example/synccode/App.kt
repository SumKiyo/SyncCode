package com.example.synccode

import android.app.Application
import androidx.room.Room
import com.example.synccode.data.AppDatabase

class App : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "synccode.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }
}