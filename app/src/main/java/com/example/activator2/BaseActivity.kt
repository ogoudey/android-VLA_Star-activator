package com.example.activator2

import android.content.Intent
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity

abstract class BaseActivity : AppCompatActivity() {

    companion object {
        val PAGES = listOf("Chat", "Web", "Modules")
    }

    fun setupSpinner(currentIndex: Int) {
        val spinner = findViewById<Spinner>(R.id.pageSpinner)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, PAGES)
        spinner.setSelection(currentIndex)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, pos: Int, id: Long) {
                if (pos == currentIndex) return // already here
                spinner.setSelection(currentIndex) // reset to avoid re-fire
                navigateTo(pos)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun navigateTo(pos: Int) {
        val target = when (pos) {
            0 -> if (this is MainActivity) null else Intent(this, MainActivity::class.java)
            1 -> if (this is WebActivity) null else Intent(this, WebActivity::class.java)
            2 -> if (this is ModulesActivity) null else Intent(this, ModulesActivity::class.java)
            else -> null
        }
        target?.let {
            startActivity(it)
            finish() // optional: removes current from back stack
        }
    }
}