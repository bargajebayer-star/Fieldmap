package com.fieldmap.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    data class Place(
        val id: Long,
        val name: String,
        val cat: String,
        val lat: Double,
        val lng: Double,
        val acc: Float,
        val note: String,
        val date: String
    )

    private val categories = listOf(
        "गट नोंदणी (NBCDC SEED)",
        "कम्युनिटी रजिस्ट्रेशन",
        "वृक्षारोपण स्थळ",
        "इतर"
    )

    private val places = mutableListOf<Place>()
    private var curLat: Double? = null
    private var curLng: Double? = null
    private var curAcc: Float = 0f

    private lateinit var etMapName: EditText
    private lateinit var etName: EditText
    private lateinit var etNote: EditText
    private lateinit var spCat: Spinner
    private lateinit var btnGps: Button
    private lateinit var tvGps: TextView
    private lateinit var tvCount: TextView
    private lateinit var llList: LinearLayout

    private var locationListener: LocationListener? = null
    private val handler = Handler(Looper.getMainLooper())
    private var bestFix: Location? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) {
                startLocationFetch()
            } else {
                tvGps.text = "लोकेशनची परवानगी मिळाली नाही. फोनच्या सेटिंगमध्ये परवानगी द्या."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etMapName = findViewById(R.id.etMapName)
        etName = findViewById(R.id.etName)
        etNote = findViewById(R.id.etNote)
        spCat = findViewById(R.id.spCat)
        btnGps = findViewById(R.id.btnGps)
        tvGps = findViewById(R.id.tvGps)
        tvCount = findViewById(R.id.tvCount)
        llList = findViewById(R.id.llList)

        spCat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)

        loadState()
        renderList()

        btnGps.setOnClickListener { requestLocation() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { savePlace() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportFiles() }
        findViewById<Button>(R.id.btnMyMaps).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/d/")))
        }
    }

    override fun onPause() {
        super.onPause()
        prefs().edit().putString("mapName", etMapName.text.toString()).apply()
    }

    override fun onDestroy() {
        stopLocationFetch()
        super.onDestroy()
    }

    // ---------- state ----------

    private fun prefs() = getSharedPreferences("fieldmap", Context.MODE_PRIVATE)

    private fun today(): String = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date())

    private fun loadState() {
        val p = prefs()
        etMapName.setText(p.getString("mapName", null) ?: "Plantation Project ${today()}")
        places.clear()
        try {
            val arr = JSONArray(p.getString("places", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                places.add(
                    Place(
                        o.getLong("id"), o.getString("name"), o.getString("cat"),
                        o.getDouble("lat"), o.getDouble("lng"), o.optDouble("acc", 0.0).toFloat(),
                        o.optString("note", ""), o.optString("date", "")
                    )
                )
            }
        } catch (e: Exception) {
            // ignore corrupt data
        }
    }

    private fun saveState() {
        val arr = JSONArray()
        for (pl in places) {
            arr.put(
                JSONObject()
                    .put("id", pl.id).put("name", pl.name).put("cat", pl.cat)
                    .put("lat", pl.lat).put("lng", pl.lng).put("acc", pl.acc.toDouble())
                    .put("note", pl.note).put("date", pl.date)
            )
        }
        prefs().edit()
            .putString("places", arr.toString())
            .putString("mapName", etMapName.text.toString())
            .apply()
    }

    // ---------- location ----------

    private fun requestLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            startLocationFetch()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationFetch() {
        stopLocationFetch()
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { lm.isProviderEnabled(it) }
        if (providers.isEmpty()) {
            tvGps.text = "फोनचे Location (GPS) बंद आहे. ते चालू करा."
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        bestFix = null
        btnGps.isEnabled = false
        tvGps.text = "लोकेशन शोधत आहे… मोकळ्या जागेत उभे रहा."

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val best = bestFix
                if (best == null || location.accuracy < best.accuracy) bestFix = location
                val b = bestFix!!
                tvGps.text = "शोधत आहे… अचूकता ±${b.accuracy.toInt()} मी"
                if (b.accuracy <= 20f) finishLocationFetch()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        locationListener = listener
        for (p in providers) {
            lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
        }
        // Use the best fix after 25 seconds even if accuracy is low.
        handler.postDelayed({ finishLocationFetch() }, 25_000L)
    }

    private fun finishLocationFetch() {
        val fix = bestFix
        stopLocationFetch()
        btnGps.isEnabled = true
        if (fix == null) {
            tvGps.text = "लोकेशन मिळाले नाही. मोकळ्या जागेत जाऊन पुन्हा प्रयत्न करा."
            return
        }
        curLat = fix.latitude
        curLng = fix.longitude
        curAcc = fix.accuracy
        tvGps.text = String.format(
            Locale.US, "%.6f, %.6f  (±%d मी)", fix.latitude, fix.longitude, fix.accuracy.toInt()
        )
    }

    private fun stopLocationFetch() {
        handler.removeCallbacksAndMessages(null)
        val l = locationListener
        if (l != null) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(l)
        }
        locationListener = null
    }

    // ---------- list ----------

    private fun savePlace() {
        val name = etName.text.toString().trim()
        val lat = curLat
        val lng = curLng
        if (name.isEmpty()) {
            toast("नाव लिहा")
            return
        }
        if (lat == null || lng == null) {
            toast("आधी 'माझे लोकेशन घ्या' दाबा")
            return
        }
        places.add(
            Place(
                System.currentTimeMillis(), name, spCat.selectedItem.toString(),
                lat, lng, curAcc, etNote.text.toString().trim(), today()
            )
        )
        saveState()
        etName.setText("")
        etNote.setText("")
        curLat = null
        curLng = null
        tvGps.text = "लोकेशन अजून घेतलेले नाही"
        renderList()
        toast("'$name' जोडले")
    }

    private fun renderList() {
        llList.removeAllViews()
        tvCount.text = "ठिकाणांची यादी (${places.size})"
        if (places.isEmpty()) {
            llList.addView(TextView(this).apply {
                text = "अजून ठिकाण जोडलेले नाही."
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        for (pl in places) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val info = TextView(this).apply {
                text = "${pl.name}\n${pl.cat} · ${pl.date}\n" +
                        String.format(Locale.US, "%.5f, %.5f (±%d मी)", pl.lat, pl.lng, pl.acc.toInt()) +
                        (if (pl.note.isNotEmpty()) "\n${pl.note}" else "")
                textSize = 14f
            }
            row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val del = Button(this).apply {
                text = "काढा"
                setOnClickListener { confirmDelete(pl) }
            }
            row.addView(del, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            llList.addView(row)
            llList.addView(View(this).apply { setBackgroundColor(0x33888888) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        }
    }

    private fun confirmDelete(pl: Place) {
        AlertDialog.Builder(this)
            .setMessage("'${pl.name}' यादीतून काढायचे?")
            .setPositiveButton("काढा") { _, _ ->
                places.removeAll { it.id == pl.id }
                saveState()
                renderList()
            }
            .setNegativeButton("रद्द करा", null)
            .show()
    }

    // ---------- export ----------

    private fun exportFiles() {
        if (places.isEmpty()) {
            toast("आधी किमान एक ठिकाण जोडा")
            return
        }
        saveState()
        val mapName = etMapName.text.toString().trim().ifEmpty { "FieldMap" }
        val base = mapName.replace(Regex("[\\\\/:*?\"<>|]+"), "").trim().ifEmpty { "FieldMap" }

        val dir = File(cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val csvFile = File(dir, "$base.csv").apply { writeText("﻿" + buildCsv(), Charsets.UTF_8) }
        val kmlFile = File(dir, "$base.kml").apply { writeText(buildKml(mapName), Charsets.UTF_8) }

        val authority = "$packageName.fileprovider"
        val uris = arrayListOf(
            FileProvider.getUriForFile(this, authority, kmlFile),
            FileProvider.getUriForFile(this, authority, csvFile)
        )
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, mapName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "फाईल कुठे पाठवायची?"))
    }

    private fun csvCell(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    private fun buildCsv(): String {
        val sb = StringBuilder("Name,Category,Latitude,Longitude,Accuracy_m,Description,Date\r\n")
        for (p in places) {
            sb.append(
                listOf(
                    p.name, p.cat,
                    String.format(Locale.US, "%.7f", p.lat),
                    String.format(Locale.US, "%.7f", p.lng),
                    p.acc.toInt().toString(), p.note, p.date
                ).joinToString(",") { csvCell(it) }
            ).append("\r\n")
        }
        return sb.toString()
    }

    private fun xml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun buildKml(mapName: String): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
        sb.append("<name>").append(xml(mapName)).append("</name>\n")
        for (cat in places.map { it.cat }.distinct()) {
            sb.append("<Folder>\n<name>").append(xml(cat)).append("</name>\n")
            for (p in places.filter { it.cat == cat }) {
                val desc = buildString {
                    append(p.date)
                    if (p.note.isNotEmpty()) append(" · ").append(p.note)
                }
                sb.append("<Placemark>\n<name>").append(xml(p.name)).append("</name>\n")
                sb.append("<description>").append(xml(desc)).append("</description>\n")
                sb.append("<Point><coordinates>")
                    .append(String.format(Locale.US, "%.7f,%.7f,0", p.lng, p.lat))
                    .append("</coordinates></Point>\n</Placemark>\n")
            }
            sb.append("</Folder>\n")
        }
        sb.append("</Document>\n</kml>\n")
        return sb.toString()
    }

    // ---------- helpers ----------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
