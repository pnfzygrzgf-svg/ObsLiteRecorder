package com.example.obsliterecorder

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // Views aus activity_about.xml holen
        val tvAboutIntro: TextView = findViewById(R.id.tvAboutIntro)
        val tvAboutCredits: TextView = findViewById(R.id.tvAboutCredits)
        val tvAboutLicense: TextView = findViewById(R.id.tvAboutLicense)

        // Intro-Text mit HTML-Link aus Strings setzen
        tvAboutIntro.text = HtmlCompat.fromHtml(
            getString(R.string.about_app_intro),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        tvAboutIntro.movementMethod = LinkMovementMethod.getInstance()

        // Falls in den Texten Links vorkommen, anklickbar machen
        tvAboutCredits.movementMethod = LinkMovementMethod.getInstance()
        tvAboutLicense.movementMethod = LinkMovementMethod.getInstance()
    }
}
