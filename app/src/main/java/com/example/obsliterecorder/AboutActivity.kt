package com.example.obsliterecorder

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // Referenzen auf die TextViews aus activity_about.xml
        val tvAboutIntro: TextView = findViewById(R.id.tvAboutIntro)
        val tvAboutCredits: TextView = findViewById(R.id.tvAboutCredits)
        val tvAboutLicense: TextView = findViewById(R.id.tvAboutLicense)

        // Falls in den Strings Links enthalten sind, anklickbar machen
        tvAboutCredits.movementMethod = LinkMovementMethod.getInstance()
        tvAboutLicense.movementMethod = LinkMovementMethod.getInstance()

        // Optional: Wenn du HTML in den Strings verwendest, könntest du hier noch
        // Html.fromHtml(...) einsetzen. Beispiel:
        //
        // tvAboutIntro.text = Html.fromHtml(getString(R.string.about_app_intro), Html.FROM_HTML_MODE_LEGACY)
        //
        // Da du den alten Code nicht mitgeschickt hast, lasse ich das neutral.
    }
}
