package com.fieldmap.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.tabs.TabLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    data class Place(
        val id: Long,
        val farmer: String,
        val village: String,
        val cat: String,
        val lat: Double,
        val lng: Double,
        val acc: Float,
        val note: String,
        val date: String,
        val photo: String,
        val employee: String = "",
        var synced: Boolean = false
    )

    private val categories = listOf(
        "वृक्षारोपण स्थळ",
        "इतर"
    )

    private val places = mutableListOf<Place>()
    private var curLat: Double? = null
    private var curLng: Double? = null
    private var curAcc: Float = 0f
    private var curPhoto: String? = null
    private var pendingPhoto: File? = null

    private lateinit var tabs: TabLayout
    private lateinit var svAdd: View
    private lateinit var svList: View
    private lateinit var svSettings: View
    private lateinit var etEmployee: EditText
    private lateinit var etScriptUrl: EditText
    private lateinit var etKey: EditText
    private lateinit var tvSync: TextView
    private lateinit var tvSettingsStatus: TextView
    private lateinit var btnSync: Button
    private var syncing = false
    private lateinit var etFarmer: EditText
    private lateinit var etVillage: EditText
    private lateinit var etNote: EditText
    private lateinit var spCat: Spinner
    private lateinit var btnGps: Button
    private lateinit var tvGps: TextView
    private lateinit var ivPhoto: ImageView
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

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val f = pendingPhoto
            pendingPhoto = null
            if (ok && f != null && f.exists() && f.length() > 0) {
                replaceCurrentPhoto(f.name)
            } else {
                f?.delete()
            }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                try {
                    val f = newPhotoFile()
                    contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { out -> input.copyTo(out) }
                    }
                    if (f.length() > 0) replaceCurrentPhoto(f.name) else f.delete()
                } catch (e: Exception) {
                    toast("फोटो घेता आला नाही")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabs = findViewById(R.id.tabs)
        svAdd = findViewById(R.id.svAdd)
        svList = findViewById(R.id.svList)
        etFarmer = findViewById(R.id.etFarmer)
        etVillage = findViewById(R.id.etVillage)
        etNote = findViewById(R.id.etNote)
        spCat = findViewById(R.id.spCat)
        btnGps = findViewById(R.id.btnGps)
        tvGps = findViewById(R.id.tvGps)
        ivPhoto = findViewById(R.id.ivPhoto)
        tvCount = findViewById(R.id.tvCount)
        llList = findViewById(R.id.llList)
        svSettings = findViewById(R.id.svSettings)
        etEmployee = findViewById(R.id.etEmployee)
        etScriptUrl = findViewById(R.id.etScriptUrl)
        etKey = findViewById(R.id.etKey)
        tvSync = findViewById(R.id.tvSync)
        tvSettingsStatus = findViewById(R.id.tvSettingsStatus)
        btnSync = findViewById(R.id.btnSync)

        spCat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)

        tabs.addTab(tabs.newTab().setText("ठिकाण जोडा"))
        tabs.addTab(tabs.newTab().setText("यादी (0)"))
        tabs.addTab(tabs.newTab().setText("सेटिंग"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showTab(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        loadState()
        loadSettings()
        renderList()
        syncPending()

        btnSync.setOnClickListener { syncPending() }
        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testConnection() }
        btnGps.setOnClickListener { requestLocation() }
        findViewById<Button>(R.id.btnPhoto).setOnClickListener { choosePhotoSource() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { savePlace() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportFiles(false) }
        findViewById<Button>(R.id.btnExportPhotos).setOnClickListener { exportFiles(true) }
        findViewById<Button>(R.id.btnMyMaps).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/d/")))
        }
    }

    override fun onDestroy() {
        stopLocationFetch()
        super.onDestroy()
    }

    private fun showTab(position: Int) {
        svAdd.visibility = if (position == 0) View.VISIBLE else View.GONE
        svList.visibility = if (position == 1) View.VISIBLE else View.GONE
        svSettings.visibility = if (position == 2) View.VISIBLE else View.GONE
    }

    // ---------- state ----------

    private fun prefs() = getSharedPreferences("fieldmap", Context.MODE_PRIVATE)

    private fun today(): String = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date())

    private fun mapName(): String = "Plantation Project ${today()}"

    private fun loadState() {
        places.clear()
        try {
            val arr = JSONArray(prefs().getString("places", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                places.add(
                    Place(
                        o.getLong("id"),
                        o.optString("farmer", o.optString("name", "")),
                        o.optString("village", ""),
                        o.optString("cat", ""),
                        o.getDouble("lat"), o.getDouble("lng"), o.optDouble("acc", 0.0).toFloat(),
                        o.optString("note", ""), o.optString("date", ""), o.optString("photo", ""),
                        o.optString("employee", ""), o.optBoolean("synced", false)
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
                    .put("id", pl.id).put("farmer", pl.farmer).put("village", pl.village)
                    .put("cat", pl.cat).put("lat", pl.lat).put("lng", pl.lng)
                    .put("acc", pl.acc.toDouble())
                    .put("note", pl.note).put("date", pl.date).put("photo", pl.photo)
                    .put("employee", pl.employee).put("synced", pl.synced)
            )
        }
        prefs().edit().putString("places", arr.toString()).apply()
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

    // ---------- add place ----------

    private fun savePlace() {
        val farmer = etFarmer.text.toString().trim()
        val village = etVillage.text.toString().trim()
        val lat = curLat
        val lng = curLng
        if (farmer.isEmpty()) {
            toast("शेतकऱ्याचे नाव लिहा")
            return
        }
        if (village.isEmpty()) {
            toast("ठिकाण (गाव) लिहा")
            return
        }
        if (employee().isEmpty()) {
            toast("आधी सेटिंग टॅबमध्ये तुमचे नाव लिहा")
            return
        }
        if (lat == null || lng == null) {
            toast("आधी 'माझे लोकेशन घ्या' दाबा")
            return
        }

        val date = today()
        val photoName = curPhoto
        if (photoName != null) {
            val stampLines = listOf(
                "शेतकरी: $farmer",
                "ठिकाण: $village",
                String.format(Locale.US, "%.6f, %.6f (±%d मी)", lat, lng, curAcc.toInt()),
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date())
            )
            if (!stampPhoto(photoFile(photoName), stampLines, lat, lng)) {
                toast("फोटोवर लोकेशन छापता आले नाही, फोटो तसाच जोडला")
            }
        }

        places.add(
            Place(
                System.currentTimeMillis(), farmer, village, spCat.selectedItem.toString(),
                lat, lng, curAcc, etNote.text.toString().trim(), date, photoName ?: "",
                employee(), false
            )
        )
        saveState()
        etFarmer.setText("")
        etVillage.setText("")
        etNote.setText("")
        curLat = null
        curLng = null
        tvGps.text = "लोकेशन अजून घेतलेले नाही"
        curPhoto = null
        updatePhotoPreview()
        renderList()
        toast("'$farmer' जोडले. यादी टॅबमध्ये पहा.")
        syncPending()
    }

    // ---------- list tab ----------

    private fun renderList() {
        llList.removeAllViews()
        tvCount.text = "जोडलेली ठिकाणे (${places.size})"
        tabs.getTabAt(1)?.text = "यादी (${places.size})"
        btnSync.text = "सिंक करा (${places.count { !it.synced }} बाकी)"
        if (places.isEmpty()) {
            llList.addView(TextView(this).apply {
                text = "अजून ठिकाण जोडलेले नाही."
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }
        for (pl in places.reversed()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { openInMaps(pl) }
            }
            if (pl.photo.isNotEmpty()) {
                val thumb = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = "फोटो"
                    decodeThumb(photoFile(pl.photo), 200)?.let { setImageBitmap(it) }
                }
                row.addView(thumb, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(10) })
            }
            val info = TextView(this).apply {
                text = "${pl.farmer}\n${pl.village} · ${pl.date}" +
                        (if (pl.employee.isNotEmpty()) " · ${pl.employee}" else "") +
                        (if (pl.synced) " · ✓ सिंक" else " · ⏳ बाकी") + "\n" +
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

    private fun openInMaps(pl: Place) {
        val label = Uri.encode("${pl.farmer}, ${pl.village}")
        val uri = Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f(%s)", pl.lat, pl.lng, pl.lat, pl.lng, label))
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            val web = Uri.parse(String.format(Locale.US, "https://www.google.com/maps/search/?api=1&query=%f,%f", pl.lat, pl.lng))
            startActivity(Intent(Intent.ACTION_VIEW, web))
        }
    }

    private fun confirmDelete(pl: Place) {
        AlertDialog.Builder(this)
            .setMessage("'${pl.farmer}' यादीतून काढायचे?")
            .setPositiveButton("काढा") { _, _ ->
                if (pl.photo.isNotEmpty()) photoFile(pl.photo).delete()
                places.removeAll { it.id == pl.id }
                saveState()
                renderList()
            }
            .setNegativeButton("रद्द करा", null)
            .show()
    }

    // ---------- export ----------

    private fun exportFiles(includePhotos: Boolean) {
        if (places.isEmpty()) {
            toast("आधी किमान एक ठिकाण जोडा")
            return
        }
        val mapName = mapName()
        val base = "Plantation Project ${SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())}"

        val dir = File(cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val csvFile = File(dir, "$base.csv").apply { writeText("﻿" + buildCsv(), Charsets.UTF_8) }
        val kmlFile = File(dir, "$base.kml").apply { writeText(buildKml(mapName), Charsets.UTF_8) }

        val authority = "$packageName.fileprovider"
        val uris = arrayListOf(
            FileProvider.getUriForFile(this, authority, kmlFile),
            FileProvider.getUriForFile(this, authority, csvFile)
        )
        for (pl in places) {
            if (includePhotos && pl.photo.isNotEmpty() && photoFile(pl.photo).exists()) {
                uris.add(FileProvider.getUriForFile(this, authority, photoFile(pl.photo)))
            }
        }
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
        val sb = StringBuilder("Name,Village,Category,Latitude,Longitude,Accuracy_m,Description,Date,Photo,Employee\r\n")
        for (p in places) {
            sb.append(
                listOf(
                    p.farmer, p.village, p.cat,
                    String.format(Locale.US, "%.7f", p.lat),
                    String.format(Locale.US, "%.7f", p.lng),
                    p.acc.toInt().toString(), p.note, p.date, p.photo, p.employee
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
                    append("ठिकाण: ").append(p.village).append(" · ").append(p.date)
                    if (p.note.isNotEmpty()) append(" · ").append(p.note)
                    if (p.photo.isNotEmpty()) append(" · फोटो: ").append(p.photo)
                }
                sb.append("<Placemark>\n<name>").append(xml(p.farmer)).append("</name>\n")
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

    // ---------- photos ----------

    private fun photoDir(): File = File(filesDir, "photos").apply { mkdirs() }

    private fun photoFile(name: String): File = File(photoDir(), name)

    private fun newPhotoFile(): File = File(photoDir(), "photo_${System.currentTimeMillis()}.jpg")

    private fun choosePhotoSource() {
        AlertDialog.Builder(this)
            .setTitle("फोटो कुठून घ्यायचा?")
            .setItems(arrayOf("कॅमेरा", "गॅलरी")) { _, which ->
                if (which == 0) {
                    try {
                        val f = newPhotoFile()
                        pendingPhoto = f
                        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                        takePicture.launch(uri)
                    } catch (e: Exception) {
                        toast("कॅमेरा उघडता आला नाही")
                    }
                } else {
                    pickImage.launch("image/*")
                }
            }
            .show()
    }

    private fun replaceCurrentPhoto(name: String) {
        curPhoto?.let { photoFile(it).delete() }
        curPhoto = name
        updatePhotoPreview()
    }

    private fun updatePhotoPreview() {
        val name = curPhoto
        if (name == null) {
            ivPhoto.visibility = View.GONE
            ivPhoto.setImageDrawable(null)
        } else {
            val bmp = decodeThumb(photoFile(name), 400)
            if (bmp != null) {
                ivPhoto.setImageBitmap(bmp)
                ivPhoto.visibility = View.VISIBLE
            }
        }
    }

    private fun exifDegrees(f: File): Float = try {
        when (ExifInterface(f.path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (e: Exception) {
        0f
    }

    private fun decodeSampled(f: File, maxSide: Int): Bitmap? {
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val degrees = exifDegrees(f)
        if (degrees == 0f) return bmp
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun decodeThumb(f: File, size: Int): Bitmap? = decodeSampled(f, size)

    /**
     * Burns the farmer, place, coordinates and time into the bottom of the photo and
     * writes the GPS position into the JPEG's EXIF data (geotag).
     */
    private fun stampPhoto(f: File, lines: List<String>, lat: Double, lng: Double): Boolean {
        return try {
            val src = decodeSampled(f, 1600) ?: return false
            val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(bmp)
            val textSize = maxOf(24f, bmp.width / 26f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                this.textSize = textSize
                setShadowLayer(3f, 1f, 1f, Color.BLACK)
            }
            val lineH = textSize * 1.3f
            val pad = textSize * 0.6f
            val boxH = lineH * lines.size + pad * 2
            val bg = Paint().apply { color = Color.argb(150, 0, 0, 0) }
            canvas.drawRect(0f, bmp.height - boxH, bmp.width.toFloat(), bmp.height.toFloat(), bg)
            var y = bmp.height - boxH + pad + textSize
            for (line in lines) {
                canvas.drawText(line, pad, y, paint)
                y += lineH
            }
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            val exif = ExifInterface(f.path)
            exif.setLatLong(lat, lng)
            exif.setAttribute(
                ExifInterface.TAG_DATETIME,
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            )
            exif.saveAttributes()
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- settings and Google Sheet sync ----------

    private fun employee(): String = prefs().getString("employee", "")?.trim() ?: ""
    private fun scriptUrl(): String = prefs().getString("scriptUrl", "")?.trim() ?: ""
    private fun scriptKey(): String = prefs().getString("scriptKey", "")?.trim() ?: ""

    private fun loadSettings() {
        etEmployee.setText(employee())
        etScriptUrl.setText(scriptUrl())
        etKey.setText(scriptKey())
    }

    private fun saveSettings() {
        prefs().edit()
            .putString("employee", etEmployee.text.toString().trim())
            .putString("scriptUrl", etScriptUrl.text.toString().trim())
            .putString("scriptKey", etKey.text.toString().trim())
            .apply()
        tvSettingsStatus.text = "सेव्ह झाले ✓"
        syncPending()
    }

    private fun testConnection() {
        saveSettings()
        val url = scriptUrl()
        val key = scriptKey()
        if (url.isEmpty() || key.isEmpty()) {
            tvSettingsStatus.text = "URL आणि गुप्त शब्द दोन्ही भरा"
            return
        }
        tvSettingsStatus.text = "तपासत आहे…"
        Thread {
            val err = postJson(url, JSONObject().put("key", key).put("action", "ping"))
            runOnUiThread {
                tvSettingsStatus.text = if (err == null) "जोडणी यशस्वी ✓" else "अयशस्वी: $err"
            }
        }.start()
    }

    private fun payload(p: Place, key: String): JSONObject {
        val emp = p.employee.ifEmpty { employee() }
        val o = JSONObject()
            .put("key", key)
            .put("id", "$emp-${p.id}")
            .put("date", p.date)
            .put("employee", emp)
            .put("farmer", p.farmer)
            .put("village", p.village)
            .put("category", p.cat)
            .put("lat", p.lat)
            .put("lng", p.lng)
            .put("accuracy", p.acc.toInt())
            .put("note", p.note)
        if (p.photo.isNotEmpty()) {
            val f = photoFile(p.photo)
            if (f.exists()) {
                o.put("photoName", p.photo)
                o.put("photoBase64", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
            }
        }
        return o
    }

    /** Returns null on success, otherwise a short error text in Marathi. */
    private fun postJson(url: String, body: JSONObject): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 90000
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            when {
                text.contains("\"ok\":true") -> null
                text.contains("\"ok\":false") -> JSONObject(text).optString("error", "त्रुटी")
                text.contains("<html", ignoreCase = true) ->
                    "URL चुकीची आहे किंवा स्क्रिप्टला 'Anyone' प्रवेश दिलेला नाही"
                else -> "अनपेक्षित उत्तर (कोड $code)"
            }
        } catch (e: Exception) {
            "इंटरनेट किंवा जोडणीची अडचण"
        }
    }

    private fun syncPending() {
        val url = scriptUrl()
        val key = scriptKey()
        if (url.isEmpty() || key.isEmpty()) {
            tvSync.text = "Google Sheet सेट केलेली नाही (सेटिंग टॅब). नोंदी फक्त या फोनवर आहेत."
            return
        }
        if (syncing) return
        val pending = places.filter { !it.synced }.toList()
        if (pending.isEmpty()) {
            tvSync.text = "सर्व नोंदी सिंक झाल्या ✓"
            return
        }
        syncing = true
        tvSync.text = "सिंक करत आहे… (${pending.size})"
        Thread {
            val done = mutableListOf<Long>()
            var err: String? = null
            for (p in pending) {
                val e = try {
                    postJson(url, payload(p, key))
                } catch (ex: Exception) {
                    "त्रुटी"
                }
                if (e == null) done.add(p.id) else {
                    err = e
                    break
                }
            }
            runOnUiThread {
                syncing = false
                places.filter { it.id in done }.forEach { it.synced = true }
                saveState()
                renderList()
                tvSync.text = if (err == null) "सर्व नोंदी सिंक झाल्या ✓"
                else "सिंक अपूर्ण: $err. नंतर पुन्हा 'सिंक करा' दाबा."
            }
        }.start()
    }

    // ---------- helpers ----------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
