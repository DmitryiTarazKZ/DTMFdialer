package com.mcal.dtmf

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

// Эта Activity обязательна для статуса "SMS-приложение по умолчанию"
class ComposeSmsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Нам не нужно ничего показывать пользователю,
        // поэтому просто закрываем экран, если он вдруг откроется.
        finish()
    }
}