package com.example.obsliterecorder

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // Views aus activity_about.xml holen
        val tvAboutIntro: TextView = findViewById(R.id.tvAboutIntro)
        val tvAboutCredits: TextView = findViewById(R.id.tvAboutCredits)
        val tvAboutLicense: TextView = findViewById(R.id.tvAboutLicense)

        // Falls in den Texten Links vorkommen, anklickbar machen
        tvAboutCredits.movementMethod = LinkMovementMethod.getInstance()
        tvAboutLicense.movementMethod = LinkMovementMethod.getInstance()

        // Optional: Intro-Text dynamisch setzen, falls gewünscht
        // tvAboutIntro.text = getString(R.string.about_app_intro)
    }
}
