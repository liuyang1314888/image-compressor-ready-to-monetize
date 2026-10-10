package com.imagecompressor.app

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.android.billingclient.api.*
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import java.io.ByteArrayOutputStream
import java.io.InputStream

class MainActivity : AppCompatActivity(), PurchasesUpdatedListener {

    private lateinit var adView: AdView
    private var interstitialAd: InterstitialAd? = null
    private lateinit var billingClient: BillingClient
    private var removeAdsSku: SkuDetails? = null
    private lateinit var statusView: TextView
    private var adsEnabled = true

    private val pickImages = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) {
            Toast.makeText(this, "未选择图片", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        Thread {
            var savedCount = 0
            for (uri in uris) {
                val result = compressAndSave(uri)
                if (result) savedCount++
            }

            runOnUiThread {
                statusView.text = "已压缩 $savedCount 张图片，保存到相册"
                if (adsEnabled) {
                    interstitialAd?.show(this)
                }
                Toast.makeText(this, "已压缩 $savedCount 张图片", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusText)
        adView = findViewById(R.id.adView)

        val prefs = getSharedPreferences("app_state", MODE_PRIVATE)
        adsEnabled = !prefs.getBoolean("remove_ads_purchased", false)
        if (!adsEnabled) hideAds()

        MobileAds.initialize(this) {}
        loadBannerAd()
        loadInterstitialAd()

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            pickImages.launch(arrayOf("image/*"))
        }

        findViewById<Button>(R.id.btnBuy).setOnClickListener {
            if (removeAdsSku == null) {
                Toast.makeText(this, "正在准备购买商品，请稍后再试", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val params = BillingFlowParams.newBuilder().setSkuDetails(removeAdsSku!!).build()
            billingClient.launchBillingFlow(this, params)
        }

        setupBilling()
    }

    private fun loadBannerAd() {
        val adRequest = AdRequest.Builder().build()
        adView.adListener = object : AdListener() {
            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                Log.w("AdMob", "Banner fail: ${loadAdError.message}")
            }
        }
        adView.adSize = AdSize.BANNER
        adView.loadAd(adRequest)
    }

    private fun loadInterstitialAd() {
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(this, "ca-app-pub-3940256099942544/1033173712", adRequest, object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                interstitialAd = ad
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                interstitialAd = null
                Log.w("AdMob", "Interstitial fail: ${error.message}")
            }
        })
    }

    private fun setupBilling() {
        billingClient = BillingClient.newBuilder(this)
            .enablePendingPurchases()
            .setListener(this)
            .build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    val params = SkuDetailsParams.newBuilder()
                        .setSkusList(listOf("remove_ads"))
                        .setType(BillingClient.SkuType.INAPP)
                        .build()

                    billingClient.querySkuDetailsAsync(params) { skuResult, skuList ->
                        if (skuResult.responseCode == BillingClient.BillingResponseCode.OK && skuList != null) {
                            removeAdsSku = skuList.firstOrNull()
                            if (removeAdsSku == null) {
                                statusView.text = "购买商品尚未创建，请在 Play Console 中创建 remove_ads 商品"
                            }
                        }
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w("Billing", "Disconnected")
            }
        })
    }

    private fun compressAndSave(uri: Uri): Boolean {
        return try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap == null) return false

            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output)
            val bytes = output.toByteArray()

            val fileName = "compressed_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/ImageCompressor")
                }
            }

            val outUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            outUri?.let { target ->
                contentResolver.openOutputStream(target)?.use { os ->
                    os.write(bytes)
                    os.flush()
                }
            }

            bitmap.recycle()
            true
        } catch (e: Exception) {
            Log.e("Compress", "Failed: ${e.message}")
            false
        }
    }

    private fun hideAds() {
        adsEnabled = false
        adView.visibility = View.GONE
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    val prefs = getSharedPreferences("app_state", MODE_PRIVATE)
                    prefs.edit().putBoolean("remove_ads_purchased", true).apply()
                    hideAds()
                    if (!purchase.isAcknowledged) {
                        val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken)
                            .build()
                        billingClient.acknowledgePurchase(acknowledgeParams) { ackResult ->
                            if (ackResult.responseCode == BillingClient.BillingResponseCode.OK) {
                                Toast.makeText(this, "已解除广告", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }
}
