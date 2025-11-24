package com.example.obsliterecorder

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // Optional: Toolbar-Back-Button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.about_app_title)

        // Intro mit HTML-Link (OpenBikeSensor Lite)
        val introView = findViewById<TextView>(R.id.tvAboutIntro)
        introView.text = HtmlCompat.fromHtml(
            getString(R.string.about_app_intro),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        introView.movementMethod = LinkMovementMethod.getInstance()
        introView.linksClickable = true

        // Credits & License: Links (GitHub-URLs) anklickbar machen
        val creditsView = findViewById<TextView>(R.id.tvAboutCredits)
        creditsView.movementMethod = LinkMovementMethod.getInstance()
        creditsView.linksClickable = true

        val licenseView = findViewById<TextView>(R.id.tvAboutLicense)
        licenseView.movementMethod = LinkMovementMethod.getInstance()
        licenseView.linksClickable = true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            finish()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }
}

